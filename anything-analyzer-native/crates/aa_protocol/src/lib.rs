use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

pub fn unix_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct OperationId(pub String);

impl OperationId {
    pub fn new(prefix: &str) -> Self {
        Self(format!("{prefix}-{}", Uuid::new_v4()))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RequestRecord {
    pub id: String,
    pub session_id: String,
    pub sequence: i64,
    pub timestamp: i64,
    pub method: String,
    pub url: String,
    pub request_headers: String,
    pub request_body: Option<String>,
    pub status_code: Option<i32>,
    pub response_headers: String,
    pub response_body: Option<String>,
    pub content_type: Option<String>,
    pub initiator: Option<String>,
    pub duration_ms: Option<i64>,
    pub is_streaming: bool,
    pub is_websocket: bool,
    pub source: String,
}

impl RequestRecord {
    pub fn new(session_id: impl Into<String>, method: impl Into<String>, url: impl Into<String>) -> Self {
        Self {
            id: String::new(),
            session_id: session_id.into(),
            sequence: 0,
            timestamp: unix_millis(),
            method: method.into(),
            url: url.into(),
            request_headers: String::new(),
            request_body: None,
            status_code: None,
            response_headers: String::new(),
            response_body: None,
            content_type: None,
            initiator: None,
            duration_ms: None,
            is_streaming: false,
            is_websocket: false,
            source: "cdp".to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HookRecord {
    pub session_id: String,
    pub timestamp: i64,
    pub hook_type: String,
    pub function_name: String,
    pub arguments: String,
    pub result: Option<String>,
    pub call_stack: Option<String>,
    pub related_request_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StorageSnapshot {
    pub session_id: String,
    pub timestamp: i64,
    pub domain: String,
    pub storage_type: String,
    pub data: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct InteractionEvent {
    pub session_id: String,
    pub sequence: i64,
    pub event_type: String,
    pub timestamp: i64,
    pub x: Option<f64>,
    pub y: Option<f64>,
    pub selector: Option<String>,
    pub xpath: Option<String>,
    pub tag_name: Option<String>,
    pub element_text: Option<String>,
    pub input_value: Option<String>,
    pub key: Option<String>,
    pub scroll_x: Option<f64>,
    pub scroll_y: Option<f64>,
    pub url: String,
    pub page_title: Option<String>,
    pub path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SessionSummary {
    pub id: String,
    pub name: String,
    pub target_url: String,
    pub status: String,
    pub created_at: i64,
    pub stopped_at: Option<i64>,
    pub request_count: i64,
    pub hook_count: i64,
    pub storage_count: i64,
    pub interaction_count: i64,
}
