// my-domain Android core (Rust, exposed to Kotlin over JNI).
//
// Mirrors the desktop backend: login (JWT) + encryption key gate, registry-based
// discovery (register on login / network change, fetch peers — no polling of the
// server), manual LAN scan, and end-to-end encrypted messaging over UDP / TCP /
// LAN WebSocket. The server is a directory only; it never sees plaintext or keys.
//
// Kotlin drives this by calling the `native*` functions and polling
// `nativeGetPeers` / `nativePollMessages`.

use std::collections::{HashMap, VecDeque};
use std::io::{ErrorKind, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::{engine::general_purpose::STANDARD, Engine};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    XChaCha20Poly1305, XNonce,
};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{info, warn};
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, Socket, Type};

const MCAST_GROUP: Ipv4Addr = Ipv4Addr::new(239, 255, 42, 98);
const DISCOVERY_PORT: u16 = 45678;
const INBOX_CAP: usize = 500;

static WS_CONN_SEQ: AtomicU64 = AtomicU64::new(1);

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

#[derive(Clone, Serialize)]
struct Identity {
    node_id: String,
    name: String,
    ip: String,
    tcp_port: u16,
    udp_port: u16,
    ws_port: u16,
}

#[derive(Serialize, Deserialize)]
struct Beacon {
    node_id: String,
    name: String,
    tcp_port: u16,
    udp_port: u16,
    ws_port: u16,
    #[serde(default)]
    reply: bool,
}

#[derive(Clone, Serialize)]
struct Peer {
    node_id: String,
    name: String,
    ip: String,
    tcp_port: u16,
    udp_port: u16,
    ws_port: u16,
    source: String,
}

#[derive(Serialize, Deserialize)]
struct Plaintext {
    from: String,
    text: String,
}

#[derive(Serialize, Deserialize)]
struct Envelope {
    nonce: String,
    ciphertext: String,
}

#[derive(Clone, Serialize)]
struct IncomingMessage {
    from: String,
    ip: String,
    protocol: String,
    text: String,
    ts: u64,
    ok: bool,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type")]
enum WsFrame {
    #[serde(rename = "hello")]
    Hello { node_id: String, name: String },
    #[serde(rename = "msg")]
    Msg { nonce: String, ciphertext: String },
}

type PeerMap = Arc<Mutex<HashMap<String, Peer>>>;
type Inbox = Arc<Mutex<VecDeque<IncomingMessage>>>;
type KeyHolder = Arc<Mutex<Option<[u8; 32]>>>;
type WsConns = Arc<Mutex<HashMap<String, (u64, mpsc::Sender<String>)>>>;

struct Session {
    server_url: Mutex<Option<String>>,
    token: Mutex<Option<String>>,
    username: Mutex<Option<String>>,
    key: KeyHolder,
}

struct NetState {
    identity: Arc<Mutex<Identity>>,
    peers: PeerMap,
    inbox: Inbox,
    disco_send: Arc<UdpSocket>,
    ws_conns: WsConns,
    session: Session,
}

static STATE: OnceLock<NetState> = OnceLock::new();

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn push_inbox(inbox: &Inbox, msg: IncomingMessage) {
    let mut q = inbox.lock().unwrap();
    if q.len() >= INBOX_CAP {
        q.pop_front();
    }
    q.push_back(msg);
}

// ---------------------------------------------------------------------------
// E2EE
// ---------------------------------------------------------------------------

fn derive_key(passphrase: &str, username: &str) -> Option<[u8; 32]> {
    let salt = format!("my-domain-e2ee:{username}");
    let mut key = [0u8; 32];
    argon2::Argon2::default()
        .hash_password_into(passphrase.as_bytes(), salt.as_bytes(), &mut key)
        .ok()?;
    Some(key)
}

fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Option<Envelope> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).ok()?;
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut nonce);
    let ct = cipher.encrypt(XNonce::from_slice(&nonce), plaintext).ok()?;
    Some(Envelope {
        nonce: STANDARD.encode(nonce),
        ciphertext: STANDARD.encode(ct),
    })
}

fn decrypt(key: &[u8; 32], env: &Envelope) -> Option<Vec<u8>> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).ok()?;
    let nonce = STANDARD.decode(&env.nonce).ok()?;
    if nonce.len() != 24 {
        return None;
    }
    let ct = STANDARD.decode(&env.ciphertext).ok()?;
    cipher.decrypt(XNonce::from_slice(&nonce), ct.as_ref()).ok()
}

fn envelope_to_message(key: &KeyHolder, env: &Envelope, ip: String, protocol: &str) -> IncomingMessage {
    if let Some(k) = key.lock().unwrap().as_ref() {
        if let Some(pt) = decrypt(k, env) {
            if let Ok(msg) = serde_json::from_slice::<Plaintext>(&pt) {
                return IncomingMessage {
                    from: msg.from,
                    ip,
                    protocol: protocol.into(),
                    text: msg.text,
                    ts: now_secs(),
                    ok: true,
                };
            }
        }
    }
    IncomingMessage {
        from: "(unknown)".into(),
        ip,
        protocol: protocol.into(),
        text: "🔒 message could not be decrypted (wrong encryption key)".into(),
        ts: now_secs(),
        ok: false,
    }
}

// ---------------------------------------------------------------------------
// Registry HTTP client
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct TokenResp {
    token: String,
    username: String,
}

#[derive(Deserialize)]
struct RegistryDevice {
    node_id: String,
    name: String,
    ip: String,
    tcp_port: u16,
    udp_port: u16,
    #[serde(default)]
    ws_port: u16,
}

fn base(url: &str) -> String {
    url.trim_end_matches('/').to_string()
}

fn http_err(e: ureq::Error) -> String {
    match e {
        ureq::Error::Status(code, r) => r
            .into_json::<serde_json::Value>()
            .ok()
            .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(String::from))
            .unwrap_or_else(|| format!("server returned HTTP {code}")),
        other => other.to_string(),
    }
}

fn auth_call(url: &str, path: &str, username: &str, password: &str) -> Result<TokenResp, String> {
    ureq::post(&format!("{}{}", base(url), path))
        .timeout(Duration::from_secs(10))
        .send_json(serde_json::json!({ "username": username, "password": password }))
        .map_err(http_err)?
        .into_json::<TokenResp>()
        .map_err(|e| e.to_string())
}

fn verify_password_call(url: &str, username: &str, password: &str) -> Result<bool, String> {
    match ureq::post(&format!("{}/auth/verify", base(url)))
        .timeout(Duration::from_secs(10))
        .send_json(serde_json::json!({ "username": username, "password": password }))
    {
        Ok(_) => Ok(true),
        Err(ureq::Error::Status(401, _)) => Ok(false),
        Err(e) => Err(http_err(e)),
    }
}

fn registry_register(url: &str, token: &str, id: &Identity) -> Result<(), String> {
    ureq::post(&format!("{}/devices/register", base(url)))
        .timeout(Duration::from_secs(10))
        .set("Authorization", &format!("Bearer {token}"))
        .send_json(serde_json::json!({
            "node_id": id.node_id, "name": id.name, "ip": id.ip,
            "tcp_port": id.tcp_port, "udp_port": id.udp_port, "ws_port": id.ws_port,
        }))
        .map_err(http_err)?;
    Ok(())
}

fn registry_fetch(url: &str, token: &str, exclude: &str) -> Result<Vec<RegistryDevice>, String> {
    ureq::get(&format!("{}/devices?exclude={}", base(url), exclude))
        .timeout(Duration::from_secs(10))
        .set("Authorization", &format!("Bearer {token}"))
        .call()
        .map_err(http_err)?
        .into_json::<Vec<RegistryDevice>>()
        .map_err(|e| e.to_string())
}

fn apply_registry_peers(peers: &PeerMap, devices: Vec<RegistryDevice>) {
    let mut map = peers.lock().unwrap();
    map.retain(|_, p| p.source != "registry");
    for d in devices {
        map.insert(
            d.node_id.clone(),
            Peer {
                node_id: d.node_id,
                name: d.name,
                ip: d.ip,
                tcp_port: d.tcp_port,
                udp_port: d.udp_port,
                ws_port: d.ws_port,
                source: "registry".into(),
            },
        );
    }
}

/// Register + fetch peers (used after login and on network change).
fn do_refresh(state: &NetState) -> Result<(), String> {
    let url = state.session.server_url.lock().unwrap().clone().ok_or("not logged in")?;
    let token = state.session.token.lock().unwrap().clone().ok_or("not logged in")?;
    let id = state.identity.lock().unwrap().clone();
    registry_register(&url, &token, &id)?;
    let devices = registry_fetch(&url, &token, &id.node_id)?;
    apply_registry_peers(&state.peers, devices);
    Ok(())
}

// ---------------------------------------------------------------------------
// LAN discovery
// ---------------------------------------------------------------------------

fn local_ip() -> String {
    UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("8.8.8.8:80")?;
            Ok(s.local_addr()?.ip().to_string())
        })
        .unwrap_or_else(|_| "0.0.0.0".to_string())
}

fn bind_multicast(iface: Ipv4Addr) -> std::io::Result<UdpSocket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    socket.set_reuse_port(true)?;
    socket.bind(&SocketAddr::new(Ipv4Addr::UNSPECIFIED.into(), DISCOVERY_PORT).into())?;
    socket.join_multicast_v4(&MCAST_GROUP, &iface)?;
    socket.set_multicast_if_v4(&iface)?;
    socket.set_multicast_loop_v4(true)?;
    Ok(socket.into())
}

fn lan_targets(ip: &str) -> Vec<SocketAddr> {
    let mut v = vec![
        SocketAddr::new(MCAST_GROUP.into(), DISCOVERY_PORT),
        SocketAddr::new(Ipv4Addr::BROADCAST.into(), DISCOVERY_PORT),
    ];
    if let Ok(v4) = ip.parse::<Ipv4Addr>() {
        let o = v4.octets();
        v.push(SocketAddr::new(Ipv4Addr::new(o[0], o[1], o[2], 255).into(), DISCOVERY_PORT));
        for host in 1..=254u8 {
            if host == o[3] {
                continue;
            }
            v.push(SocketAddr::new(Ipv4Addr::new(o[0], o[1], o[2], host).into(), DISCOVERY_PORT));
        }
    }
    v
}

fn send_beacon(socket: &UdpSocket, id: &Identity, reply: bool, to: &[SocketAddr]) {
    let beacon = Beacon {
        node_id: id.node_id.clone(),
        name: id.name.clone(),
        tcp_port: id.tcp_port,
        udp_port: id.udp_port,
        ws_port: id.ws_port,
        reply,
    };
    if let Ok(payload) = serde_json::to_vec(&beacon) {
        let _ = socket.set_broadcast(true);
        for dst in to {
            if let Err(e) = socket.send_to(&payload, dst) {
                if !matches!(e.raw_os_error(), Some(64) | Some(65) | Some(51)) {
                    warn!("beacon send to {dst}: {e}");
                }
            }
        }
    }
}

fn discovery_recv_loop(recv: UdpSocket, send: Arc<UdpSocket>, peers: PeerMap, identity: Arc<Mutex<Identity>>) {
    let mut buf = [0u8; 2048];
    loop {
        let (len, src) = match recv.recv_from(&mut buf) {
            Ok(v) => v,
            Err(_) => continue,
        };
        let beacon: Beacon = match serde_json::from_slice(&buf[..len]) {
            Ok(b) => b,
            Err(_) => continue,
        };
        if beacon.node_id == identity.lock().unwrap().node_id {
            continue;
        }
        peers.lock().unwrap().insert(
            beacon.node_id.clone(),
            Peer {
                node_id: beacon.node_id.clone(),
                name: beacon.name.clone(),
                ip: src.ip().to_string(),
                tcp_port: beacon.tcp_port,
                udp_port: beacon.udp_port,
                ws_port: beacon.ws_port,
                source: "lan".into(),
            },
        );
        if !beacon.reply {
            let id = identity.lock().unwrap().clone();
            send_beacon(&send, &id, true, &[SocketAddr::new(src.ip(), DISCOVERY_PORT)]);
        }
    }
}

// ---------------------------------------------------------------------------
// Messaging — UDP / TCP
// ---------------------------------------------------------------------------

fn tcp_recv_loop(listener: TcpListener, inbox: Inbox, key: KeyHolder) {
    for stream in listener.incoming() {
        let Ok(mut stream) = stream else { continue };
        let inbox = inbox.clone();
        let key = key.clone();
        std::thread::spawn(move || {
            let ip = stream.peer_addr().map(|a| a.ip().to_string()).unwrap_or_default();
            let mut buf = Vec::new();
            if stream.read_to_end(&mut buf).is_err() {
                return;
            }
            if let Ok(env) = serde_json::from_slice::<Envelope>(&buf) {
                push_inbox(&inbox, envelope_to_message(&key, &env, ip, "TCP"));
            }
        });
    }
}

fn udp_recv_loop(socket: UdpSocket, inbox: Inbox, key: KeyHolder) {
    let mut buf = [0u8; 65535];
    loop {
        let (len, src) = match socket.recv_from(&mut buf) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if let Ok(env) = serde_json::from_slice::<Envelope>(&buf[..len]) {
            push_inbox(&inbox, envelope_to_message(&key, &env, src.ip().to_string(), "UDP"));
        }
    }
}

// ---------------------------------------------------------------------------
// Messaging — WebSocket (LAN, persistent, bidirectional, one socket per peer)
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct WsCtx {
    identity: Arc<Mutex<Identity>>,
    key: KeyHolder,
    inbox: Inbox,
    conns: WsConns,
}

fn ws_server_loop(listener: TcpListener, ctx: WsCtx) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let _ = stream.set_read_timeout(Some(Duration::from_millis(200)));
        if let Ok(ws) = tungstenite::accept(stream) {
            let ctx = ctx.clone();
            std::thread::spawn(move || handle_ws_conn(ws, ctx));
        }
    }
}

fn handle_ws_conn<S: Read + Write>(mut ws: tungstenite::WebSocket<S>, ctx: WsCtx) {
    {
        let id = ctx.identity.lock().unwrap();
        let hello = serde_json::to_string(&WsFrame::Hello {
            node_id: id.node_id.clone(),
            name: id.name.clone(),
        })
        .unwrap_or_default();
        let _ = ws.send(tungstenite::Message::Text(hello));
    }
    let (tx, rx) = mpsc::channel::<String>();
    let my_conn_id = WS_CONN_SEQ.fetch_add(1, Ordering::Relaxed);
    let mut peer_id: Option<String> = None;
    loop {
        match ws.read() {
            Ok(tungstenite::Message::Text(t)) => {
                if let Ok(frame) = serde_json::from_str::<WsFrame>(&t) {
                    match frame {
                        WsFrame::Hello { node_id, .. } => {
                            let mut guard = ctx.conns.lock().unwrap();
                            if guard.contains_key(&node_id) {
                                break;
                            }
                            guard.insert(node_id.clone(), (my_conn_id, tx.clone()));
                            drop(guard);
                            peer_id = Some(node_id);
                        }
                        WsFrame::Msg { nonce, ciphertext } => {
                            let env = Envelope { nonce, ciphertext };
                            push_inbox(&ctx.inbox, envelope_to_message(&ctx.key, &env, String::new(), "WS"));
                        }
                    }
                }
            }
            Ok(tungstenite::Message::Close(_)) => break,
            Ok(_) => {}
            Err(tungstenite::Error::Io(e))
                if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::TimedOut => {}
            Err(_) => break,
        }
        let mut dead = false;
        while let Ok(out) = rx.try_recv() {
            if ws.send(tungstenite::Message::Text(out)).is_err() {
                dead = true;
                break;
            }
        }
        if dead {
            break;
        }
    }
    if let Some(pid) = peer_id {
        let mut g = ctx.conns.lock().unwrap();
        if g.get(&pid).map(|(id, _)| *id == my_conn_id).unwrap_or(false) {
            g.remove(&pid);
        }
    }
}

fn ws_connect(ctx: WsCtx, ip: &str, ws_port: u16) -> Result<(), String> {
    let addr: Ipv4Addr = ip.parse().map_err(|_| "bad peer ip")?;
    let stream = TcpStream::connect_timeout(&SocketAddr::new(addr.into(), ws_port), Duration::from_secs(4))
        .map_err(|e| format!("WS connect failed: {e}"))?;
    stream.set_read_timeout(Some(Duration::from_millis(200))).map_err(|e| e.to_string())?;
    let req = format!("ws://{ip}:{ws_port}/");
    let (ws, _resp) = tungstenite::client(req.as_str(), stream).map_err(|e| format!("WS handshake failed: {e}"))?;
    std::thread::spawn(move || handle_ws_conn(ws, ctx));
    Ok(())
}

// ---------------------------------------------------------------------------
// Core actions (shared by JNI shims)
// ---------------------------------------------------------------------------

fn do_send(state: &NetState, node_id: &str, protocol: &str, text: &str) -> Result<(), String> {
    let key = state.session.key.lock().unwrap().ok_or("set your encryption key first")?;
    let from = state
        .session
        .username
        .lock()
        .unwrap()
        .clone()
        .unwrap_or_else(|| state.identity.lock().unwrap().name.clone());
    let plaintext = serde_json::to_vec(&Plaintext { from, text: text.to_string() }).map_err(|e| e.to_string())?;
    let env = encrypt(&key, &plaintext).ok_or("encryption failed")?;
    let body = serde_json::to_vec(&env).map_err(|e| e.to_string())?;
    let proto = protocol.to_uppercase();

    if proto == "WS" {
        let frame = serde_json::to_string(&WsFrame::Msg {
            nonce: env.nonce.clone(),
            ciphertext: env.ciphertext.clone(),
        })
        .map_err(|e| e.to_string())?;
        let sender = state
            .ws_conns
            .lock()
            .unwrap()
            .get(node_id)
            .map(|(_, s)| s.clone())
            .ok_or("no WebSocket connection — connect first")?;
        sender.send(frame).map_err(|_| "WebSocket closed".to_string())?;
        return Ok(());
    }

    let peer = state.peers.lock().unwrap().get(node_id).cloned().ok_or("peer not found")?;
    let ip: Ipv4Addr = peer.ip.parse().map_err(|_| "bad peer ip")?;
    match proto.as_str() {
        "TCP" => {
            let mut stream = TcpStream::connect_timeout(&SocketAddr::new(ip.into(), peer.tcp_port), Duration::from_secs(3))
                .map_err(|e| format!("TCP connect failed: {e}"))?;
            stream.write_all(&body).map_err(|e| format!("TCP send failed: {e}"))?;
            stream.shutdown(std::net::Shutdown::Write).map_err(|e| e.to_string())?;
            Ok(())
        }
        "UDP" => {
            let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
            socket
                .send_to(&body, SocketAddr::new(ip.into(), peer.udp_port))
                .map_err(|e| format!("UDP send failed: {e}"))?;
            Ok(())
        }
        other => Err(format!("unknown protocol: {other}")),
    }
}

fn build_state(name: String, wifi_ip: String) -> NetState {
    let node_id = uuid::Uuid::new_v4().to_string();
    let iface: Ipv4Addr = wifi_ip
        .parse()
        .unwrap_or_else(|_| local_ip().parse().unwrap_or(Ipv4Addr::UNSPECIFIED));
    let ip_display = if iface.is_unspecified() { local_ip() } else { iface.to_string() };

    let tcp_listener = TcpListener::bind("0.0.0.0:0").expect("tcp");
    let tcp_port = tcp_listener.local_addr().expect("tcp addr").port();
    let udp_msg_socket = UdpSocket::bind("0.0.0.0:0").expect("udp");
    let udp_port = udp_msg_socket.local_addr().expect("udp addr").port();
    let ws_listener = TcpListener::bind("0.0.0.0:0").expect("ws");
    let ws_port = ws_listener.local_addr().expect("ws addr").port();

    let identity = Arc::new(Mutex::new(Identity {
        node_id,
        name,
        ip: ip_display,
        tcp_port,
        udp_port,
        ws_port,
    }));
    info!("identity {:?}", identity.lock().unwrap().ip);

    let peers: PeerMap = Arc::new(Mutex::new(HashMap::new()));
    let inbox: Inbox = Arc::new(Mutex::new(VecDeque::new()));
    let ws_conns: WsConns = Arc::new(Mutex::new(HashMap::new()));
    let key: KeyHolder = Arc::new(Mutex::new(None));

    let (disco_send, disco_ok): (Arc<UdpSocket>, bool) = match bind_multicast(iface) {
        Ok(recv) => {
            let send = Arc::new(recv.try_clone().expect("clone disco"));
            {
                let send2 = send.clone();
                let peers = peers.clone();
                let identity = identity.clone();
                std::thread::spawn(move || discovery_recv_loop(recv, send2, peers, identity));
            }
            (send, true)
        }
        Err(e) => {
            warn!("multicast bind failed: {e}");
            (Arc::new(UdpSocket::bind("0.0.0.0:0").expect("fallback udp")), false)
        }
    };
    let _ = disco_ok;

    {
        let inbox = inbox.clone();
        let key = key.clone();
        std::thread::spawn(move || tcp_recv_loop(tcp_listener, inbox, key));
    }
    {
        let inbox = inbox.clone();
        let key = key.clone();
        std::thread::spawn(move || udp_recv_loop(udp_msg_socket, inbox, key));
    }
    {
        let ctx = WsCtx {
            identity: identity.clone(),
            key: key.clone(),
            inbox: inbox.clone(),
            conns: ws_conns.clone(),
        };
        std::thread::spawn(move || ws_server_loop(ws_listener, ctx));
    }

    NetState {
        identity,
        peers,
        inbox,
        disco_send,
        ws_conns,
        session: Session {
            server_url: Mutex::new(None),
            token: Mutex::new(None),
            username: Mutex::new(None),
            key,
        },
    }
}

// ---------------------------------------------------------------------------
// JNI surface — package com.mydomain.android, class RustNet
// ---------------------------------------------------------------------------

fn jstr(env: &mut JNIEnv, s: &JString, fallback: &str) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_else(|_| fallback.to_string())
}

fn ret(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|o| o.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn ok_or_err(r: Result<(), String>) -> String {
    match r {
        Ok(()) => String::new(),
        Err(e) => format!("ERROR: {e}"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeStart<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    name: JString<'l>,
    wifi_ip: JString<'l>,
) -> jstring {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("mydomain_net"),
    );
    let name = jstr(&mut env, &name, "android");
    let wifi_ip = jstr(&mut env, &wifi_ip, "");
    let state = STATE.get_or_init(|| build_state(name, wifi_ip));
    let json = serde_json::to_string(&state.identity.lock().unwrap().clone()).unwrap_or_else(|_| "{}".into());
    ret(&mut env, json)
}

fn auth_flow(env: &mut JNIEnv, path: &str, url: JString, user: JString, pass: JString) -> String {
    let url = jstr(env, &url, "");
    let user = jstr(env, &user, "");
    let pass = jstr(env, &pass, "");
    let Some(state) = STATE.get() else { return "ERROR: not started".into() };
    if url.trim().is_empty() {
        return "ERROR: server URL required".into();
    }
    match auth_call(&url, path, user.trim(), &pass) {
        Ok(resp) => {
            *state.session.server_url.lock().unwrap() = Some(base(&url));
            *state.session.token.lock().unwrap() = Some(resp.token);
            *state.session.username.lock().unwrap() = Some(resp.username);
            String::new()
        }
        Err(e) => format!("ERROR: {e}"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeAuthLogin<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, url: JString<'l>, user: JString<'l>, pass: JString<'l>,
) -> jstring {
    let r = auth_flow(&mut env, "/auth/login", url, user, pass);
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeAuthRegister<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, url: JString<'l>, user: JString<'l>, pass: JString<'l>,
) -> jstring {
    let r = auth_flow(&mut env, "/auth/register", url, user, pass);
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSetEncryptionKey<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, passphrase: JString<'l>,
) -> jstring {
    let passphrase = jstr(&mut env, &passphrase, "");
    let r = (|| {
        let state = STATE.get().ok_or("not started")?;
        let username = state.session.username.lock().unwrap().clone().ok_or("log in first")?;
        if passphrase.trim().is_empty() {
            return Err("encryption key required".to_string());
        }
        let key = derive_key(&passphrase, &username).ok_or("failed to derive key")?;
        *state.session.key.lock().unwrap() = Some(key);
        Ok(())
    })();
    let out = ok_or_err(r);
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeUpdateEncryptionKey<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, new_pass: JString<'l>, password: JString<'l>,
) -> jstring {
    let new_pass = jstr(&mut env, &new_pass, "");
    let password = jstr(&mut env, &password, "");
    let r = (|| {
        let state = STATE.get().ok_or("not started")?;
        let username = state.session.username.lock().unwrap().clone().ok_or("log in first")?;
        let url = state.session.server_url.lock().unwrap().clone().ok_or("no server")?;
        if !verify_password_call(&url, &username, &password)? {
            return Err("incorrect password".to_string());
        }
        if new_pass.trim().is_empty() {
            return Err("new key required".to_string());
        }
        let key = derive_key(&new_pass, &username).ok_or("failed to derive key")?;
        *state.session.key.lock().unwrap() = Some(key);
        Ok(())
    })();
    let out = ok_or_err(r);
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeLogout<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    if let Some(state) = STATE.get() {
        *state.session.token.lock().unwrap() = None;
        *state.session.key.lock().unwrap() = None;
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeGenerateKey<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let mut bytes = [0u8; 24];
    OsRng.fill_bytes(&mut bytes);
    ret(&mut env, STANDARD.encode(bytes))
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeIsReady<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let ready = STATE
        .get()
        .map(|s| s.session.token.lock().unwrap().is_some() && s.session.key.lock().unwrap().is_some())
        .unwrap_or(false);
    ret(&mut env, if ready { "true".into() } else { "false".into() })
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeRefresh<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let r = STATE.get().ok_or("not started".to_string()).and_then(do_refresh);
    let out = ok_or_err(r);
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeNetworkChanged<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, wifi_ip: JString<'l>,
) -> jstring {
    let wifi_ip = jstr(&mut env, &wifi_ip, "");
    if let Some(state) = STATE.get() {
        if let Ok(v4) = wifi_ip.parse::<Ipv4Addr>() {
            state.identity.lock().unwrap().ip = v4.to_string();
        }
        let ready = state.session.token.lock().unwrap().is_some();
        if ready {
            let _ = do_refresh(state);
        }
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeScanLan<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    if let Some(state) = STATE.get() {
        let id = state.identity.lock().unwrap().clone();
        let socket = state.disco_send.clone();
        let targets = lan_targets(&id.ip);
        std::thread::spawn(move || {
            for _ in 0..3 {
                send_beacon(&socket, &id, false, &targets);
                std::thread::sleep(Duration::from_millis(700));
            }
        });
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeConnectWs<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, node_id: JString<'l>,
) -> jstring {
    let node_id = jstr(&mut env, &node_id, "");
    let r = (|| {
        let state = STATE.get().ok_or("not started")?;
        let peer = state.peers.lock().unwrap().get(&node_id).cloned().ok_or("peer not found")?;
        if peer.ws_port == 0 {
            return Err("peer has no WebSocket port".to_string());
        }
        if state.ws_conns.lock().unwrap().contains_key(&node_id) {
            return Ok(());
        }
        let ctx = WsCtx {
            identity: state.identity.clone(),
            key: state.session.key.clone(),
            inbox: state.inbox.clone(),
            conns: state.ws_conns.clone(),
        };
        ws_connect(ctx, &peer.ip, peer.ws_port)
    })();
    let out = ok_or_err(r);
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSend<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, node_id: JString<'l>, protocol: JString<'l>, text: JString<'l>,
) -> jstring {
    let node_id = jstr(&mut env, &node_id, "");
    let protocol = jstr(&mut env, &protocol, "UDP");
    let text = jstr(&mut env, &text, "");
    let r = STATE
        .get()
        .ok_or("not started".to_string())
        .and_then(|s| do_send(s, &node_id, &protocol, &text));
    let out = ok_or_err(r);
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeGetPeers<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let json = STATE
        .get()
        .map(|s| {
            let list: Vec<Peer> = s.peers.lock().unwrap().values().cloned().collect();
            serde_json::to_string(&list).unwrap_or_else(|_| "[]".into())
        })
        .unwrap_or_else(|| "[]".into());
    ret(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativePollMessages<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let json = STATE
        .get()
        .map(|s| {
            let drained: Vec<IncomingMessage> = s.inbox.lock().unwrap().drain(..).collect();
            serde_json::to_string(&drained).unwrap_or_else(|_| "[]".into())
        })
        .unwrap_or_else(|| "[]".into());
    ret(&mut env, json)
}
