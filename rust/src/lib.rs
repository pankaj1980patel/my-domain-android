// my-domain Android core — a thin JNI adapter over the shared `mdcore` Engine.
//
// Platform specifics (device id + name + Wi-Fi IP passed from Kotlin, the
// single-interface multicast strategy, and the poll-based inbox) live here; all
// networking, crypto, discovery, and messaging live in `mdcore`.
//
// Kotlin drives this by calling the `native*` functions and polling
// `nativeGetPeers` / `nativePollMessages` / `nativeConnectedPeers`.

use std::collections::VecDeque;
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::info;

use mdcore::engine::Engine;
use mdcore::events::{CoreEvent, EventSink};
use mdcore::model::IncomingMessage;
use mdcore::platform::{detect_local_ip, IfaceMode, Platform};

const INBOX_CAP: usize = 500;

// ---------------------------------------------------------------------------
// Platform implementation
// ---------------------------------------------------------------------------

struct AndroidPlatform {
    device_id: String,
    name: String,
    /// Updated on network change; shared so `nativeNetworkChanged` can mutate it.
    wifi_ip: Arc<Mutex<String>>,
    /// Set from Kotlin via `nativeSetFcmToken` once Firebase issues it.
    fcm_token: Arc<Mutex<Option<String>>>,
}

impl AndroidPlatform {
    fn iface_v4(&self) -> Ipv4Addr {
        self.wifi_ip
            .lock()
            .unwrap()
            .parse()
            .unwrap_or_else(|_| detect_local_ip().parse().unwrap_or(Ipv4Addr::UNSPECIFIED))
    }
}

impl Platform for AndroidPlatform {
    fn device_id(&self) -> String {
        self.device_id.clone()
    }
    fn device_name(&self) -> String {
        self.name.clone()
    }
    fn platform_kind(&self) -> &'static str {
        "android"
    }
    fn iface_mode(&self) -> IfaceMode {
        IfaceMode::Single(self.iface_v4())
    }
    fn local_ip(&self) -> String {
        let i = self.iface_v4();
        if i.is_unspecified() {
            detect_local_ip()
        } else {
            i.to_string()
        }
    }
    fn fcm_token(&self) -> Option<String> {
        self.fcm_token.lock().unwrap().clone()
    }
    // kv: in-memory only (None defaults). clipboard: not yet supported.
}

// ---------------------------------------------------------------------------
// Event sink — record incoming messages into an inbox drained by poll.
// ---------------------------------------------------------------------------

struct InboxSink {
    messages: Mutex<VecDeque<IncomingMessage>>,
    /// Feature events (notification / call-notification / call-history) as JSON,
    /// drained by `nativePollEvents`.
    events: Mutex<VecDeque<serde_json::Value>>,
}

impl InboxSink {
    fn push_event(&self, v: serde_json::Value) {
        let mut q = self.events.lock().unwrap();
        if q.len() >= INBOX_CAP {
            q.pop_front();
        }
        q.push_back(v);
    }
}

impl EventSink for InboxSink {
    fn emit(&self, ev: CoreEvent) {
        // Peers/WS-state are read directly off the Engine by the poll shims; chat
        // goes to the message inbox; feature events go to the events inbox.
        match ev {
            CoreEvent::MessageReceived(m) => {
                let mut q = self.messages.lock().unwrap();
                if q.len() >= INBOX_CAP {
                    q.pop_front();
                }
                q.push_back(m);
            }
            CoreEvent::Notification { from, title, body, app } => self.push_event(serde_json::json!({
                "kind": "notification", "from": from, "title": title, "body": body, "app": app,
            })),
            CoreEvent::CallNotification { from, caller, number, state } => self.push_event(serde_json::json!({
                "kind": "call_notification", "from": from, "caller": caller, "number": number, "state": state,
            })),
            CoreEvent::CallHistory { from, entries } => self.push_event(serde_json::json!({
                "kind": "call_history", "from": from, "entries": entries,
            })),
            CoreEvent::AppsList { from, apps, subscribed } => self.push_event(serde_json::json!({
                "kind": "apps_list", "from": from, "apps": apps, "subscribed": subscribed,
            })),
            // Clipboard sync + peer/WS changes are not used on android today.
            _ => {}
        }
    }
}

impl InboxSink {
    fn drain(&self) -> Vec<IncomingMessage> {
        self.messages.lock().unwrap().drain(..).collect()
    }
    fn drain_events(&self) -> Vec<serde_json::Value> {
        self.events.lock().unwrap().drain(..).collect()
    }
}

static ENGINE: OnceLock<Engine<AndroidPlatform>> = OnceLock::new();
static SINK: OnceLock<Arc<InboxSink>> = OnceLock::new();
static WIFI_IP: OnceLock<Arc<Mutex<String>>> = OnceLock::new();
static FCM_TOKEN: OnceLock<Arc<Mutex<Option<String>>>> = OnceLock::new();

fn engine() -> Option<&'static Engine<AndroidPlatform>> {
    ENGINE.get()
}

/// Shared FCM-token cell, created on first use so the token can be set before or
/// after `nativeStart`.
fn fcm_token_cell() -> &'static Arc<Mutex<Option<String>>> {
    FCM_TOKEN.get_or_init(|| Arc::new(Mutex::new(None)))
}

// ---------------------------------------------------------------------------
// JNI helpers — package com.mydomain.android, class RustNet
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

fn with_engine<F: FnOnce(&Engine<AndroidPlatform>) -> Result<(), String>>(f: F) -> String {
    match engine() {
        Some(e) => ok_or_err(f(e)),
        None => "ERROR: not started".into(),
    }
}

// ---------------------------------------------------------------------------
// JNI surface
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeStart<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    device_id: JString<'l>,
    name: JString<'l>,
    wifi_ip: JString<'l>,
) -> jstring {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("mydomain_net"),
    );
    let device_id = jstr(&mut env, &device_id, "");
    let name = jstr(&mut env, &name, "android");
    let wifi_ip = jstr(&mut env, &wifi_ip, "");

    if ENGINE.get().is_none() {
        let wifi = Arc::new(Mutex::new(wifi_ip));
        let platform = AndroidPlatform {
            device_id,
            name,
            wifi_ip: wifi.clone(),
            fcm_token: fcm_token_cell().clone(),
        };
        let sink = Arc::new(InboxSink {
            messages: Mutex::new(VecDeque::new()),
            events: Mutex::new(VecDeque::new()),
        });
        match Engine::start(platform, sink.clone() as Arc<dyn EventSink>) {
            Ok(eng) => {
                let _ = WIFI_IP.set(wifi);
                let _ = SINK.set(sink);
                let _ = ENGINE.set(eng);
                info!("engine started");
            }
            Err(e) => return ret(&mut env, format!("ERROR: {e}")),
        }
    }

    let json = engine()
        .map(|e| serde_json::to_string(&e.identity()).unwrap_or_else(|_| "{}".into()))
        .unwrap_or_else(|| "{}".into());
    ret(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeAuthLogin<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, url: JString<'l>, user: JString<'l>, pass: JString<'l>,
) -> jstring {
    let url = jstr(&mut env, &url, "");
    let user = jstr(&mut env, &user, "");
    let pass = jstr(&mut env, &pass, "");
    let out = with_engine(|e| e.auth_login(&url, &user, &pass));
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeAuthRegister<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, url: JString<'l>, user: JString<'l>, pass: JString<'l>,
) -> jstring {
    let url = jstr(&mut env, &url, "");
    let user = jstr(&mut env, &user, "");
    let pass = jstr(&mut env, &pass, "");
    let out = with_engine(|e| e.auth_register(&url, &user, &pass));
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSetEncryptionKey<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, passphrase: JString<'l>,
) -> jstring {
    let passphrase = jstr(&mut env, &passphrase, "");
    let out = with_engine(|e| e.set_encryption_key(&passphrase));
    ret(&mut env, out)
}

/// DEV / LAN-only: skip the registry login (see `Engine::dev_login`).
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeDevLogin<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, username: JString<'l>, passphrase: JString<'l>,
) -> jstring {
    let username = jstr(&mut env, &username, "");
    let passphrase = jstr(&mut env, &passphrase, "");
    let out = with_engine(|e| e.dev_login(&username, &passphrase));
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeUpdateEncryptionKey<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, new_pass: JString<'l>, password: JString<'l>,
) -> jstring {
    let new_pass = jstr(&mut env, &new_pass, "");
    let password = jstr(&mut env, &password, "");
    let out = with_engine(|e| e.update_encryption_key(&new_pass, &password));
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeLogout<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    if let Some(e) = engine() {
        e.logout();
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeGenerateKey<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    ret(&mut env, mdcore::crypto::generate_key())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeIsReady<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let ready = engine().map(|e| e.is_ready()).unwrap_or(false);
    ret(&mut env, if ready { "true".into() } else { "false".into() })
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeRefresh<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let out = with_engine(|e| e.refresh_from_server());
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeNetworkChanged<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, wifi_ip: JString<'l>,
) -> jstring {
    let wifi_ip = jstr(&mut env, &wifi_ip, "");
    if let Some(w) = WIFI_IP.get() {
        if wifi_ip.parse::<Ipv4Addr>().is_ok() {
            *w.lock().unwrap() = wifi_ip.clone();
        }
    }
    if let Some(e) = engine() {
        e.network_changed(Some(&wifi_ip));
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeScanLan<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    if let Some(e) = engine() {
        e.scan_lan();
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeConnectWs<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, node_id: JString<'l>,
) -> jstring {
    let node_id = jstr(&mut env, &node_id, "");
    let out = with_engine(|e| e.connect_ws(&node_id));
    ret(&mut env, out)
}

/// Send a message; the engine auto-selects the transport (active connection if
/// any, else the directed UDP/TCP). Returns the protocol used on success, or
/// "ERROR: …" on failure.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSend<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, node_id: JString<'l>, text: JString<'l>,
) -> jstring {
    let node_id = jstr(&mut env, &node_id, "");
    let text = jstr(&mut env, &text, "");
    let out = match engine() {
        Some(e) => match e.send(&node_id, &text) {
            Ok(proto) => proto,
            Err(err) => format!("ERROR: {err}"),
        },
        None => "ERROR: not started".into(),
    };
    ret(&mut env, out)
}

/// Directed transport ("UDP" | "TCP") used when no live connection exists.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeDirectedTransport<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let out = engine().map(|e| e.directed_transport()).unwrap_or_else(|| "UDP".into());
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSetDirectedTransport<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, transport: JString<'l>,
) -> jstring {
    let transport = jstr(&mut env, &transport, "UDP");
    if let Some(e) = engine() {
        e.set_directed_transport(&transport);
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeGetPeers<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let json = engine()
        .map(|e| serde_json::to_string(&e.get_peers()).unwrap_or_else(|_| "[]".into()))
        .unwrap_or_else(|| "[]".into());
    ret(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativePollMessages<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let json = SINK
        .get()
        .map(|s| serde_json::to_string(&s.drain()).unwrap_or_else(|_| "[]".into()))
        .unwrap_or_else(|| "[]".into());
    ret(&mut env, json)
}

/// node_ids of peers with a live WebSocket (true on the accepting side too).
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeConnectedPeers<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let json = engine()
        .map(|e| serde_json::to_string(&e.connected_peers()).unwrap_or_else(|_| "[]".into()))
        .unwrap_or_else(|| "[]".into());
    ret(&mut env, json)
}

/// Drain feature events (notification / call-notification / call-history) as a
/// JSON array; each object has a `kind` discriminator.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativePollEvents<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let json = SINK
        .get()
        .map(|s| serde_json::to_string(&s.drain_events()).unwrap_or_else(|_| "[]".into()))
        .unwrap_or_else(|| "[]".into());
    ret(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeShareNotification<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, title: JString<'l>, body: JString<'l>, app: JString<'l>,
) -> jstring {
    let title = jstr(&mut env, &title, "");
    let body = jstr(&mut env, &body, "");
    let app = jstr(&mut env, &app, "");
    if let Some(e) = engine() {
        let app = if app.is_empty() { None } else { Some(app.as_str()) };
        e.share_notification(&title, &body, app);
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeShareCallNotification<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, caller: JString<'l>, number: JString<'l>, state: JString<'l>,
) -> jstring {
    let caller = jstr(&mut env, &caller, "");
    let number = jstr(&mut env, &number, "");
    let state = jstr(&mut env, &state, "");
    if let Some(e) = engine() {
        let number = if number.is_empty() { None } else { Some(number.as_str()) };
        e.share_call_notification(&caller, number, &state);
    }
    ret(&mut env, String::new())
}

#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeShareCallHistory<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, entries_json: JString<'l>,
) -> jstring {
    let entries_json = jstr(&mut env, &entries_json, "[]");
    if let Some(e) = engine() {
        e.share_call_history(&entries_json);
    }
    ret(&mut env, String::new())
}

// --- signaling / connection setup ---

/// Set the Firebase FCM token (from Kotlin). Re-registers so the server stores
/// it. Order-independent vs `nativeStart`.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSetFcmToken<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, token: JString<'l>,
) -> jstring {
    let token = jstr(&mut env, &token, "");
    *fcm_token_cell().lock().unwrap() = if token.is_empty() { None } else { Some(token) };
    if let Some(e) = engine() {
        let _ = e.refresh_from_server();
    }
    ret(&mut env, String::new())
}

/// Feed an inbound FCM signal (from Kotlin's FirebaseMessagingService).
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeOnSignal<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, from: JString<'l>, payload: JString<'l>,
) -> jstring {
    let from = jstr(&mut env, &from, "");
    let payload = jstr(&mut env, &payload, "");
    if let Some(e) = engine() {
        e.on_signal(&from, &payload);
    }
    ret(&mut env, String::new())
}

/// Connect to a peer along the fallback ladder.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeConnect<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, node_id: JString<'l>,
) -> jstring {
    let node_id = jstr(&mut env, &node_id, "");
    let out = with_engine(|e| e.connect(&node_id));
    ret(&mut env, out)
}

/// Run the startup firewall check; returns JSON `{outbound_ok, inbound_blocked}`
/// or `ERROR: ...`.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeFirewallCheck<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>,
) -> jstring {
    let out = match engine() {
        Some(e) => match e.firewall_check() {
            Ok(fs) => serde_json::json!({ "outbound_ok": fs.outbound_ok, "inbound_blocked": fs.inbound_blocked }).to_string(),
            Err(e) => format!("ERROR: {e}"),
        },
        None => "ERROR: not started".into(),
    };
    ret(&mut env, out)
}

// --- app-notification pub/sub ---

/// Producer: publish this device's shareable app list (JSON array of {pkg,label}).
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSetInstalledApps<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, apps_json: JString<'l>,
) -> jstring {
    let apps_json = jstr(&mut env, &apps_json, "[]");
    if let Some(e) = engine() {
        e.set_installed_apps(&apps_json);
    }
    ret(&mut env, String::new())
}

/// Consumer: request a producer's app list (reply arrives via nativePollEvents).
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeRequestApps<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, node_id: JString<'l>,
) -> jstring {
    let node_id = jstr(&mut env, &node_id, "");
    let out = with_engine(|e| e.request_apps(&node_id));
    ret(&mut env, out)
}

/// Consumer: set the enabled package set (JSON array of pkg strings) on a producer.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeSubscribeApps<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, node_id: JString<'l>, apps_json: JString<'l>,
) -> jstring {
    let node_id = jstr(&mut env, &node_id, "");
    let apps_json = jstr(&mut env, &apps_json, "[]");
    let out = with_engine(|e| e.subscribe_apps(&node_id, &apps_json));
    ret(&mut env, out)
}

/// Producer: a captured app notification — forwarded to subscribers of `pkg`.
#[no_mangle]
pub extern "system" fn Java_com_mydomain_android_RustNet_nativeShareAppNotification<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, pkg: JString<'l>, app: JString<'l>, title: JString<'l>, body: JString<'l>,
) -> jstring {
    let pkg = jstr(&mut env, &pkg, "");
    let app = jstr(&mut env, &app, "");
    let title = jstr(&mut env, &title, "");
    let body = jstr(&mut env, &body, "");
    if let Some(e) = engine() {
        e.share_app_notification(&pkg, &app, &title, &body);
    }
    ret(&mut env, String::new())
}
