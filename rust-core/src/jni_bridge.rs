use crate::engine::{ActionOutcome, OpenBotEngine};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

static ENGINE_INSTANCE: OnceLock<Mutex<Option<OpenBotEngine>>> = OnceLock::new();

fn get_engine_mutex() -> &'static Mutex<Option<OpenBotEngine>> {
    ENGINE_INSTANCE.get_or_init(|| Mutex::new(None))
}

#[no_mangle]
pub extern "system" fn Java_com_openbot_mobile_OpenBotCore_nativeInitEngine(
    mut env: JNIEnv,
    _class: JClass,
    proot_path: JString,
    rootfs_path: JString,
    workspace_path: JString,
) -> jboolean {
    let proot: String = match env.get_string(&proot_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let rootfs: String = match env.get_string(&rootfs_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let workspace: String = match env.get_string(&workspace_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let engine = OpenBotEngine::new(&proot, &rootfs, &workspace);
    let mut lock = get_engine_mutex().lock().unwrap();
    *lock = Some(engine);
    1
}

#[no_mangle]
pub extern "system" fn Java_com_openbot_mobile_OpenBotCore_nativeRequestExecute(
    mut env: JNIEnv,
    _class: JClass,
    command_str: JString,
) -> jstring {
    let command: String = match env.get_string(&command_str) {
        Ok(s) => s.into(),
        Err(e) => return create_error_json(&mut env, &format!("JNI string error: {}", e)),
    };

    let lock = get_engine_mutex().lock().unwrap();
    let engine = match lock.as_ref() {
        Some(e) => e,
        None => return create_error_json(&mut env, "OpenBotEngine not initialized"),
    };

    match engine.request_execute(&command) {
        Ok(outcome) => {
            let json = serde_json::to_string(&outcome)
                .unwrap_or_else(|_| "{\"error\":\"Serialization error\"}".to_string());
            env.new_string(json).map(|s| s.into_raw()).unwrap_or_else(|_| std::ptr::null_mut())
        }
        Err(e) => create_error_json(&mut env, &e.to_string()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_openbot_mobile_OpenBotCore_nativeResolveApproval(
    mut env: JNIEnv,
    _class: JClass,
    approval_id: JString,
    approved: jboolean,
) -> jstring {
    let id: String = match env.get_string(&approval_id) {
        Ok(s) => s.into(),
        Err(e) => return create_error_json(&mut env, &format!("JNI string error: {}", e)),
    };

    let lock = get_engine_mutex().lock().unwrap();
    let engine = match lock.as_ref() {
        Some(e) => e,
        None => return create_error_json(&mut env, "OpenBotEngine not initialized"),
    };

    match engine.resolve_approval(&id, approved != 0) {
        Ok(exec_result) => {
            let json = serde_json::to_string(&ActionOutcome::Executed(exec_result))
                .unwrap_or_else(|_| "{\"error\":\"Serialization error\"}".to_string());
            env.new_string(json).map(|s| s.into_raw()).unwrap_or_else(|_| std::ptr::null_mut())
        }
        Err(e) => create_error_json(&mut env, &e.to_string()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_openbot_mobile_OpenBotCore_nativeListPendingApprovals(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let lock = get_engine_mutex().lock().unwrap();
    let engine = match lock.as_ref() {
        Some(e) => e,
        None => return create_error_json(&mut env, "OpenBotEngine not initialized"),
    };

    let approvals = engine.list_pending_approvals();
    let json = serde_json::to_string(&approvals).unwrap_or_else(|_| "[]".to_string());
    env.new_string(json).map(|s| s.into_raw()).unwrap_or_else(|_| std::ptr::null_mut())
}

fn create_error_json(env: &mut JNIEnv, err_msg: &str) -> jstring {
    let json = format!("{{\"error\":{}}}", serde_json::to_string(err_msg).unwrap_or_default());
    env.new_string(json).map(|s| s.into_raw()).unwrap_or_else(|_| std::ptr::null_mut())
}
