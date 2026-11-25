pub mod engine;
pub mod jni_bridge;
pub mod policy;
pub mod proot;

pub use engine::{ActionOutcome, EngineError, OpenBotEngine, PendingApproval};
pub use policy::{PolicyDecision, PolicyEngine, PolicyRule, RiskLevel};
pub use proot::{ExecutionResult, PRootConfig, PRootExecutor, SandboxError};

// UniFFI scaffolding setup for Android Kotlin bindings
#[cfg(feature = "uniffi-bindings")]
uniffi::setup_scaffolding!();
