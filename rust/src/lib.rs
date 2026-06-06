// LAN discovery + UDP/TCP messaging for Android, exposed to Kotlin over JNI.
//
// This speaks the exact same wire protocol as the desktop (Tauri) app, so the
// two interoperate on the same LAN:
//
//   * Discovery beacon  -> UDP multicast 239.255.42.98:45678, every 2s:
//       {"node_id","name","tcp_port","udp_port"}
//   * Message           -> JSON body of a TCP connection, OR a single UDP
//       datagram sent to the peer's advertised port:
//       {"from","text"}
//
// The Rust side runs the network loops on background threads and keeps state
// (peer table + an inbox queue) behind mutexes. Kotlin drives it by polling:
//   nativeStart(name) -> identity json   (idempotent)
//   nativeGetPeers()  -> [peer, ...]
//   nativePollMessages() -> [msg, ...]   (drains the inbox)
//   nativeSend(node_id, protocol, text) -> "" on success, "ERROR: .." otherwise
//
// NOTE: receiving multicast on Android requires a WifiManager.MulticastLock,
// which is acquired on the Kotlin side before nativeStart() is called.

use std::collections::{HashMap, VecDeque};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{info, warn};
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, Socket, Type};

const MCAST_GROUP: Ipv4Addr = Ipv4Addr::new(239, 255, 42, 98);
const DISCOVERY_PORT: u16 = 45678;
const BEACON_INTERVAL: Duration = Duration::from_secs(2);
const PEER_TIMEOUT_SECS: u64 = 8;
const INBOX_CAP: usize = 500;

#[derive(Clone, Serialize)]
struct Identity {
    node_id: String,
    name: String,
    ip: String,
    tcp_port: u16,
    udp_port: u16,
}

#[derive(Serialize, Deserialize)]
struct Beacon {
    node_id: String,
    name: String,
    tcp_port: u16,
    udp_port: u16,
}

#[derive(Clone, Serialize)]
struct Peer {
    node_id: String,
    name: String,
    ip: String,
    tcp_port: u16,
    udp_port: u16,
    last_seen: u64,
}

#[derive(Serialize, Deserialize)]
struct WireMessage {
    from: String,
    text: String,
}

#[derive(Clone, Serialize)]
struct IncomingMessage {
    from: String,
    ip: String,
    protocol: String,
    text: String,
    ts: u64,
}

struct NetState {
    identity: Identity,
    peers: Arc<Mutex<HashMap<String, Peer>>>,
    inbox: Arc<Mutex<VecDeque<IncomingMessage>>>,
}

static STATE: OnceLock<NetState> = OnceLock::new();

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Best-effort local LAN IP (no traffic is actually sent).
fn local_ip() -> String {
    UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("8.8.8.8:80")?;
            Ok(s.local_addr()?.ip().to_string())
        })
        .unwrap_or_else(|_| "0.0.0.0".to_string())
}

/// UDP socket bound to the multicast port and joined to the group, with
/// address/port reuse so it coexists with other listeners.
///
/// `iface` is the local IPv4 of the interface to use. On Android this MUST be
/// the Wi-Fi address: it both joins the group on Wi-Fi (RX) and pins outgoing
/// multicast to Wi-Fi (TX) so beacons don't leak out over cellular and vanish.
fn bind_multicast(iface: Ipv4Addr) -> std::io::Result<UdpSocket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    socket.set_reuse_port(true)?;
    let addr: SocketAddr = SocketAddr::new(Ipv4Addr::UNSPECIFIED.into(), DISCOVERY_PORT);
    socket.bind(&addr.into())?;
    socket.join_multicast_v4(&MCAST_GROUP, &iface)?;
    socket.set_multicast_if_v4(&iface)?; // send beacons out this interface (Wi-Fi)
    socket.set_multicast_loop_v4(true)?;
    info!("joined {MCAST_GROUP}:{DISCOVERY_PORT} on iface {iface}");
    Ok(socket.into())
}

fn push_inbox(inbox: &Arc<Mutex<VecDeque<IncomingMessage>>>, msg: IncomingMessage) {
    let mut q = inbox.lock().unwrap();
    if q.len() >= INBOX_CAP {
        q.pop_front();
    }
    q.push_back(msg);
}

fn discovery_recv_loop(socket: UdpSocket, peers: Arc<Mutex<HashMap<String, Peer>>>, my_id: String) {
    let mut buf = [0u8; 2048];
    loop {
        let (len, src) = match socket.recv_from(&mut buf) {
            Ok(v) => v,
            Err(e) => {
                warn!("disco recv error: {e}");
                continue;
            }
        };
        let beacon: Beacon = match serde_json::from_slice(&buf[..len]) {
            Ok(b) => b,
            Err(e) => {
                warn!("rx {len}B from {src}: not a beacon ({e})");
                continue;
            }
        };
        if beacon.node_id == my_id {
            continue;
        }
        let is_new = !peers.lock().unwrap().contains_key(&beacon.node_id);
        if is_new {
            info!(
                "NEW peer '{}' @ {} (tcp {}, udp {})",
                beacon.name,
                src.ip(),
                beacon.tcp_port,
                beacon.udp_port
            );
        }
        let peer = Peer {
            node_id: beacon.node_id.clone(),
            name: beacon.name,
            ip: src.ip().to_string(),
            tcp_port: beacon.tcp_port,
            udp_port: beacon.udp_port,
            last_seen: now_secs(),
        };
        peers.lock().unwrap().insert(beacon.node_id, peer);
    }
}

fn beacon_send_loop(socket: UdpSocket, identity: Identity) {
    let ip = identity.ip.clone();
    let beacon = Beacon {
        node_id: identity.node_id,
        name: identity.name,
        tcp_port: identity.tcp_port,
        udp_port: identity.udp_port,
    };
    let payload = serde_json::to_vec(&beacon).unwrap();
    let _ = socket.set_broadcast(true);

    // Multicast group + limited broadcast + the /24 subnet-directed broadcast.
    // Broadcast bypasses the router's IGMP snooping (which prunes multicast
    // between Wi-Fi clients); the directed broadcast routes on-link over Wi-Fi.
    let mut targets = vec![
        SocketAddr::new(MCAST_GROUP.into(), DISCOVERY_PORT),
        SocketAddr::new(Ipv4Addr::BROADCAST.into(), DISCOVERY_PORT),
    ];
    if let Ok(v4) = ip.parse::<Ipv4Addr>() {
        let o = v4.octets();
        let directed = Ipv4Addr::new(o[0], o[1], o[2], 255);
        targets.push(SocketAddr::new(directed.into(), DISCOVERY_PORT));
        // Unicast sweep of the /24. Last-resort discovery for networks that
        // drop both multicast and broadcast but allow client-to-client unicast.
        for host in 1..=254u8 {
            if host == o[3] {
                continue; // skip ourselves
            }
            let ip = Ipv4Addr::new(o[0], o[1], o[2], host);
            targets.push(SocketAddr::new(ip.into(), DISCOVERY_PORT));
        }
    }
    info!("beaconing as '{}' -> {} targets", beacon.name, targets.len());
    loop {
        for dst in &targets {
            if let Err(e) = socket.send_to(&payload, dst) {
                warn!("beacon send to {dst} error: {e}");
            }
        }
        info!("beacon round sent ({} targets)", targets.len());
        std::thread::sleep(BEACON_INTERVAL);
    }
}

fn prune_loop(peers: Arc<Mutex<HashMap<String, Peer>>>) {
    loop {
        std::thread::sleep(Duration::from_secs(2));
        let cutoff = now_secs().saturating_sub(PEER_TIMEOUT_SECS);
        peers.lock().unwrap().retain(|_, p| p.last_seen >= cutoff);
    }
}

fn tcp_recv_loop(listener: TcpListener, inbox: Arc<Mutex<VecDeque<IncomingMessage>>>) {
    for stream in listener.incoming() {
        let Ok(mut stream) = stream else { continue };
        let inbox = inbox.clone();
        std::thread::spawn(move || {
            let ip = stream
                .peer_addr()
                .map(|a| a.ip().to_string())
                .unwrap_or_default();
            let mut buf = Vec::new();
            if stream.read_to_end(&mut buf).is_err() {
                return;
            }
            if let Ok(msg) = serde_json::from_slice::<WireMessage>(&buf) {
                info!("TCP msg from {} ({}): {}", msg.from, ip, msg.text);
                push_inbox(
                    &inbox,
                    IncomingMessage {
                        from: msg.from,
                        ip,
                        protocol: "TCP".into(),
                        text: msg.text,
                        ts: now_secs(),
                    },
                );
            }
        });
    }
}

fn udp_recv_loop(socket: UdpSocket, inbox: Arc<Mutex<VecDeque<IncomingMessage>>>) {
    let mut buf = [0u8; 65535];
    loop {
        let (len, src) = match socket.recv_from(&mut buf) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if let Ok(msg) = serde_json::from_slice::<WireMessage>(&buf[..len]) {
            info!("UDP msg from {} ({}): {}", msg.from, src.ip(), msg.text);
            push_inbox(
                &inbox,
                IncomingMessage {
                    from: msg.from,
                    ip: src.ip().to_string(),
                    protocol: "UDP".into(),
                    text: msg.text,
                    ts: now_secs(),
                },
            );
        }
    }
}

/// Build the networking state and start all loops. Runs exactly once.
///
/// `wifi_ip` is the device's Wi-Fi IPv4 (from Kotlin). If empty/invalid we fall
/// back to the default-route IP, but on a phone that may be cellular — so the
/// caller should always pass the real Wi-Fi address.
fn build_state(name: String, wifi_ip: String) -> NetState {
    let node_id = uuid::Uuid::new_v4().to_string();

    // The interface to discover/advertise on. Prefer the supplied Wi-Fi IP.
    let iface: Ipv4Addr = wifi_ip
        .parse()
        .unwrap_or_else(|_| local_ip().parse().unwrap_or(Ipv4Addr::UNSPECIFIED));
    let ip_display = if iface.is_unspecified() {
        local_ip()
    } else {
        iface.to_string()
    };

    // Ephemeral TCP + UDP ports, advertised in the beacon.
    let tcp_listener = TcpListener::bind("0.0.0.0:0").expect("bind tcp");
    let tcp_port = tcp_listener.local_addr().expect("tcp addr").port();
    let udp_msg_socket = UdpSocket::bind("0.0.0.0:0").expect("bind udp");
    let udp_port = udp_msg_socket.local_addr().expect("udp addr").port();

    let identity = Identity {
        node_id: node_id.clone(),
        name,
        ip: ip_display,
        tcp_port,
        udp_port,
    };
    info!(
        "identity name='{}' ip={} tcp={} udp={} iface={} id={}",
        identity.name, identity.ip, identity.tcp_port, identity.udp_port, iface, identity.node_id
    );

    let peers: Arc<Mutex<HashMap<String, Peer>>> = Arc::new(Mutex::new(HashMap::new()));
    let inbox: Arc<Mutex<VecDeque<IncomingMessage>>> = Arc::new(Mutex::new(VecDeque::new()));

    // Discovery: one socket shared (clone) for send + receive.
    if let Ok(disco_recv) = bind_multicast(iface) {
        if let Ok(disco_send) = disco_recv.try_clone() {
            {
                let peers = peers.clone();
                let my_id = node_id.clone();
                std::thread::spawn(move || discovery_recv_loop(disco_recv, peers, my_id));
            }
            {
                let identity = identity.clone();
                std::thread::spawn(move || beacon_send_loop(disco_send, identity));
            }
        }
    }
    {
        let peers = peers.clone();
        std::thread::spawn(move || prune_loop(peers));
    }
    {
        let inbox = inbox.clone();
        std::thread::spawn(move || tcp_recv_loop(tcp_listener, inbox));
    }
    {
        let inbox = inbox.clone();
        std::thread::spawn(move || udp_recv_loop(udp_msg_socket, inbox));
    }

    NetState {
        identity,
        peers,
        inbox,
    }
}

fn ensure_started(name: String, wifi_ip: String) -> &'static NetState {
    STATE.get_or_init(move || build_state(name, wifi_ip))
}

fn do_send(node_id: &str, protocol: &str, text: &str) -> Result<(), String> {
    let state = STATE.get().ok_or("network not started")?;
    let peer = state
        .peers
        .lock()
        .unwrap()
        .get(node_id)
        .cloned()
        .ok_or("peer not found (it may have gone offline)")?;

    let payload = serde_json::to_vec(&WireMessage {
        from: state.identity.name.clone(),
        text: text.to_string(),
    })
    .map_err(|e| e.to_string())?;

    match protocol.to_uppercase().as_str() {
        "TCP" => {
            let ip: Ipv4Addr = peer.ip.parse().map_err(|_| "bad peer ip")?;
            let addr = SocketAddr::new(ip.into(), peer.tcp_port);
            let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(3))
                .map_err(|e| format!("TCP connect failed: {e}"))?;
            stream
                .write_all(&payload)
                .map_err(|e| format!("TCP send failed: {e}"))?;
            stream
                .shutdown(std::net::Shutdown::Write)
                .map_err(|e| e.to_string())?;
            Ok(())
        }
        "UDP" => {
            let ip: Ipv4Addr = peer.ip.parse().map_err(|_| "bad peer ip")?;
            let addr = SocketAddr::new(ip.into(), peer.udp_port);
            let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
            socket
                .send_to(&payload, addr)
                .map_err(|e| format!("UDP send failed: {e}"))?;
            Ok(())
        }
        other => Err(format!("unknown protocol: {other}")),
    }
}

// ---------------------------------------------------------------------------
// JNI surface — package com.mydomain.android, class RustNet
// ---------------------------------------------------------------------------

fn jstr_to_string(env: &mut JNIEnv, s: &JString, fallback: &str) -> String {
    env.get_string(s)
        .map(|v| v.into())
        .unwrap_or_else(|_| fallback.to_string())
}

fn ret_string(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s)
        .map(|o| o.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
    wifi_ip: JString<'local>,
) -> jstring {
    // Logs show up in `adb logcat -s mydomain_net` (or Logcat tag "mydomain_net").
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("mydomain_net"),
    );
    let name = jstr_to_string(&mut env, &name, "android");
    let wifi_ip = jstr_to_string(&mut env, &wifi_ip, "");
    info!("nativeStart name='{name}' wifi_ip='{wifi_ip}'");
    let state = ensure_started(name, wifi_ip);
    let json = serde_json::to_string(&state.identity).unwrap_or_else(|_| "{}".into());
    ret_string(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeGetPeers<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let json = match STATE.get() {
        Some(s) => {
            let list: Vec<Peer> = s.peers.lock().unwrap().values().cloned().collect();
            serde_json::to_string(&list).unwrap_or_else(|_| "[]".into())
        }
        None => "[]".into(),
    };
    ret_string(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativePollMessages<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let json = match STATE.get() {
        Some(s) => {
            let drained: Vec<IncomingMessage> = s.inbox.lock().unwrap().drain(..).collect();
            serde_json::to_string(&drained).unwrap_or_else(|_| "[]".into())
        }
        None => "[]".into(),
    };
    ret_string(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSend<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    node_id: JString<'local>,
    protocol: JString<'local>,
    text: JString<'local>,
) -> jstring {
    let node_id = jstr_to_string(&mut env, &node_id, "");
    let protocol = jstr_to_string(&mut env, &protocol, "UDP");
    let text = jstr_to_string(&mut env, &text, "");
    let result = match do_send(&node_id, &protocol, &text) {
        Ok(()) => String::new(),
        Err(e) => format!("ERROR: {e}"),
    };
    ret_string(&mut env, result)
}
