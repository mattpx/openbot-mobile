use crate::engine::{ActionOutcome, OpenBotEngine};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use std::sync::{Arc, Mutex, OnceLock};

type EngineSlot = Mutex<Option<Arc<OpenBotEngine>>>;

static ENGINE_INSTANCE: OnceLock<EngineSlot> = OnceLock::new();

fn engine_slot() -> &'static EngineSlot {
    ENGINE_INSTANCE.get_or_init(|| Mutex::new(None))
}

/// Clone the engine handle and release the slot lock immediately.
///
/// Command execution must never happen while this lock is held: the Android UI
/// polls pending approvals and resolves them while a command is still running.
fn clone_engine_handle(slot: &EngineSlot) -> Option<Arc<OpenBotEngine>> {
    match slot.lock() {
        Ok(guard) => guard.clone(),
        Err(poisoned) => poisoned.into_inner().clone(),
    }
}

fn to_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn outcome_to_jstring(env: &mut JNIEnv, outcome: &ActionOutcome) -> jstring {
    match serde_json::to_string(outcome) {
        Ok(json) => to_jstring(env, &json),
        Err(_) => create_error_json(env, "Serialization error"),
    }
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

    let engine = Arc::new(OpenBotEngine::new(&proot, &rootfs, &workspace));
    match engine_slot().lock() {
        Ok(mut guard) => {
            *guard = Some(engine);
            1
        }
        Err(poisoned) => {
            *poisoned.into_inner() = Some(engine);
            1
        }
    }
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

    let engine = match clone_engine_handle(engine_slot()) {
        Some(engine) => engine,
        None => return create_error_json(&mut env, "OpenBotEngine not initialized"),
    };

    match engine.request_execute(&command) {
        Ok(outcome) => outcome_to_jstring(&mut env, &outcome),
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

    let engine = match clone_engine_handle(engine_slot()) {
        Some(engine) => engine,
        None => return create_error_json(&mut env, "OpenBotEngine not initialized"),
    };

    match engine.resolve_approval(&id, approved != 0) {
        Ok(exec_result) => {
            outcome_to_jstring(&mut env, &ActionOutcome::Executed(exec_result))
        }
        Err(e) => {
            let outcome = match e {
                crate::engine::EngineError::ActionDenied(reason) => ActionOutcome::Denied { reason },
                crate::engine::EngineError::ApprovalNotFound(_) => ActionOutcome::Denied {
                    reason: e.to_string(),
                },
                other => return create_error_json(&mut env, &other.to_string()),
            };
            outcome_to_jstring(&mut env, &outcome)
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_openbot_mobile_OpenBotCore_nativeListPendingApprovals(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let engine = match clone_engine_handle(engine_slot()) {
        Some(engine) => engine,
        None => return create_error_json(&mut env, "OpenBotEngine not initialized"),
    };

    let approvals = engine.list_pending_approvals();
    match serde_json::to_string(&approvals) {
        Ok(json) => to_jstring(&mut env, &json),
        Err(_) => to_jstring(&mut env, "[]"),
    }
}

/// Returns `"proot"` for real PRoot isolation, `"shim"` for the degraded
/// fallback, or `"unavailable"` before the engine is initialized.
#[no_mangle]
pub extern "system" fn Java_com_openbot_mobile_OpenBotCore_nativeSandboxMode(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let mode = clone_engine_handle(engine_slot())
        .map(|engine| engine.sandbox_mode().to_string())
        .unwrap_or_else(|| "unavailable".to_string());
    to_jstring(&mut env, &mode)
}

#[no_mangle]
pub extern "system" fn Java_com_openbot_mobile_OpenBotCore_nativeSetCustomPolicyRules(
    mut env: JNIEnv,
    _class: JClass,
    rules_json: JString,
) -> jboolean {
    let raw: String = match env.get_string(&rules_json) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let rules: Vec<crate::policy::PolicyRule> = match serde_json::from_str(&raw) {
        Ok(r) => r,
        Err(_) => return 0,
    };
    let engine = match clone_engine_handle(engine_slot()) {
        Some(engine) => engine,
        None => return 0,
    };
    engine.set_custom_policy_rules(rules);
    1
}

fn create_error_json(env: &mut JNIEnv, err_msg: &str) -> jstring {
    let escaped = serde_json::to_string(err_msg).unwrap_or_else(|_| "\"error\"".to_string());
    let json = format!("{{\"error\":{}}}", escaped);
    to_jstring(env, &json)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    fn shim_slot(temp: &std::path::Path) -> EngineSlot {
        let proot = temp.join("proot");
        std::fs::write(&proot, b"#!/bin/sh\n# OPENBOT_SHIM\nexit 0\n").unwrap();
        let rootfs = temp.join("rootfs");
        std::fs::create_dir_all(rootfs.join("tmp")).unwrap();
        let workspace = temp.join("workspace");
        std::fs::create_dir(&workspace).unwrap();
        let engine = OpenBotEngine::new(
            proot.to_str().unwrap(),
            rootfs.to_str().unwrap(),
            workspace.to_str().unwrap(),
        );
        Mutex::new(Some(Arc::new(engine)))
    }

    #[test]
    fn cloning_handle_does_not_wait_for_running_command() {
        let temp = tempfile::tempdir().unwrap();
        let slot = shim_slot(temp.path());
        let worker = {
            let handle = clone_engine_handle(&slot).expect("engine handle");
            std::thread::spawn(move || handle.request_execute("sleep 2"))
        };

        std::thread::sleep(Duration::from_millis(150));
        let started = Instant::now();
        let handle = clone_engine_handle(&slot);
        assert!(handle.is_some());
        assert!(
            started.elapsed() < Duration::from_secs(1),
            "cloning the engine handle blocked on command execution"
        );

        let outcome = worker.join().unwrap().unwrap();
        assert!(matches!(outcome, ActionOutcome::Executed(_)));
    }

    #[test]
    fn missing_engine_returns_none() {
        let slot: EngineSlot = Mutex::new(None);
        assert!(clone_engine_handle(&slot).is_none());
    }
}
