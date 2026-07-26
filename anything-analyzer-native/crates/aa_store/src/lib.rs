use aa_protocol::{HookRecord, InteractionEvent, RequestRecord, SessionSummary, StorageSnapshot};
use rusqlite::{params, Connection, OptionalExtension, Transaction};
use serde_json::{json, Value};
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use thiserror::Error;
use uuid::Uuid;

#[derive(Debug, Error)]
pub enum StoreError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("store mutex poisoned")]
    Poisoned,
    #[error("session not found: {0}")]
    SessionNotFound(String),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, StoreError>;

pub struct Store {
    connection: Mutex<Connection>,
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let connection = Connection::open(path)?;
        connection.busy_timeout(std::time::Duration::from_secs(5))?;
        connection.execute_batch(
            "PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;",
        )?;
        Ok(Self {
            connection: Mutex::new(connection),
        })
    }

    fn connection(&self) -> Result<MutexGuard<'_, Connection>> {
        self.connection.lock().map_err(|_| StoreError::Poisoned)
    }

    pub fn migrate(&self) -> Result<()> {
        self.connection()?.execute_batch(SCHEMA)?;
        Ok(())
    }

    pub fn create_session(
        &self,
        id: &str,
        name: &str,
        target_url: &str,
        created_at: i64,
    ) -> Result<()> {
        self.connection()?.execute(
            "INSERT INTO sessions(id,name,target_url,status,created_at) VALUES(?1,?2,?3,'running',?4)\
             ON CONFLICT(id) DO UPDATE SET name=excluded.name,target_url=excluded.target_url,status='running'",
            params![id, name, target_url, created_at],
        )?;
        Ok(())
    }

    pub fn stop_session(&self, id: &str, stopped_at: i64) -> Result<()> {
        let changed = self.connection()?.execute(
            "UPDATE sessions SET status='stopped', stopped_at=?2 WHERE id=?1",
            params![id, stopped_at],
        )?;
        if changed == 0 {
            return Err(StoreError::SessionNotFound(id.to_string()));
        }
        Ok(())
    }

    pub fn add_request(&self, mut record: RequestRecord) -> Result<RequestRecord> {
        let mut connection = self.connection()?;
        let tx = connection.transaction()?;
        if record.sequence <= 0 {
            record.sequence = next_sequence(&tx, "requests", &record.session_id)?;
        }
        if record.id.is_empty() {
            record.id = format!("{}-{}-{}", record.session_id, record.sequence, Uuid::new_v4());
        }
        tx.execute(
            "INSERT INTO requests(id,session_id,sequence,timestamp,method,url,request_headers,request_body,status_code,response_headers,response_body,content_type,initiator,duration_ms,is_streaming,is_websocket,source)\
             VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17)",
            params![
                record.id,
                record.session_id,
                record.sequence,
                record.timestamp,
                record.method,
                record.url,
                record.request_headers,
                record.request_body,
                record.status_code,
                record.response_headers,
                record.response_body,
                record.content_type,
                record.initiator,
                record.duration_ms,
                i64::from(record.is_streaming),
                i64::from(record.is_websocket),
                record.source,
            ],
        )?;
        tx.commit()?;
        Ok(record)
    }

    pub fn add_hook(&self, record: &HookRecord) -> Result<i64> {
        let connection = self.connection()?;
        connection.execute(
            "INSERT INTO js_hooks(session_id,timestamp,hook_type,function_name,arguments,result,call_stack,related_request_id) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",
            params![record.session_id, record.timestamp, record.hook_type, record.function_name, record.arguments, record.result, record.call_stack, record.related_request_id],
        )?;
        Ok(connection.last_insert_rowid())
    }

    pub fn add_storage_snapshot(&self, snapshot: &StorageSnapshot) -> Result<i64> {
        let connection = self.connection()?;
        connection.execute(
            "INSERT INTO storage_snapshots(session_id,timestamp,domain,storage_type,data) VALUES(?1,?2,?3,?4,?5)",
            params![snapshot.session_id, snapshot.timestamp, snapshot.domain, snapshot.storage_type, snapshot.data],
        )?;
        Ok(connection.last_insert_rowid())
    }

    pub fn add_interaction(&self, mut event: InteractionEvent) -> Result<InteractionEvent> {
        let mut connection = self.connection()?;
        let tx = connection.transaction()?;
        if event.sequence <= 0 {
            event.sequence = next_sequence(&tx, "interaction_events", &event.session_id)?;
        }
        tx.execute(
            "INSERT INTO interaction_events(session_id,sequence,type,timestamp,x,y,selector,xpath,tag_name,element_text,input_value,key,scroll_x,scroll_y,url,page_title,path)\
             VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17)",
            params![event.session_id,event.sequence,event.event_type,event.timestamp,event.x,event.y,event.selector,event.xpath,event.tag_name,event.element_text,event.input_value,event.key,event.scroll_x,event.scroll_y,event.url,event.page_title,event.path],
        )?;
        tx.commit()?;
        Ok(event)
    }

    pub fn summary(&self, session_id: &str) -> Result<SessionSummary> {
        let connection = self.connection()?;
        connection
            .query_row(
                "SELECT s.id,COALESCE(s.name,''),COALESCE(s.target_url,''),s.status,s.created_at,s.stopped_at,\
                 (SELECT COUNT(*) FROM requests r WHERE r.session_id=s.id),\
                 (SELECT COUNT(*) FROM js_hooks h WHERE h.session_id=s.id),\
                 (SELECT COUNT(*) FROM storage_snapshots x WHERE x.session_id=s.id),\
                 (SELECT COUNT(*) FROM interaction_events e WHERE e.session_id=s.id)\
                 FROM sessions s WHERE s.id=?1",
                [session_id],
                |row| {
                    Ok(SessionSummary {
                        id: row.get(0)?,
                        name: row.get(1)?,
                        target_url: row.get(2)?,
                        status: row.get(3)?,
                        created_at: row.get(4)?,
                        stopped_at: row.get(5)?,
                        request_count: row.get(6)?,
                        hook_count: row.get(7)?,
                        storage_count: row.get(8)?,
                        interaction_count: row.get(9)?,
                    })
                },
            )
            .optional()?
            .ok_or_else(|| StoreError::SessionNotFound(session_id.to_string()))
    }

    pub fn export_session_value(&self, session_id: &str) -> Result<Value> {
        let summary = self.summary(session_id)?;
        let connection = self.connection()?;
        let mut statement = connection.prepare(
            "SELECT id,sequence,timestamp,method,url,request_headers,request_body,status_code,response_headers,response_body,content_type,initiator,duration_ms,is_streaming,is_websocket,source FROM requests WHERE session_id=?1 ORDER BY sequence",
        )?;
        let requests = statement
            .query_map([session_id], |row| {
                Ok(json!({
                    "id": row.get::<_, String>(0)?,
                    "sequence": row.get::<_, i64>(1)?,
                    "timestamp": row.get::<_, i64>(2)?,
                    "method": row.get::<_, String>(3)?,
                    "url": row.get::<_, String>(4)?,
                    "request_headers": row.get::<_, String>(5)?,
                    "request_body": row.get::<_, Option<String>>(6)?,
                    "status_code": row.get::<_, Option<i32>>(7)?,
                    "response_headers": row.get::<_, String>(8)?,
                    "response_body": row.get::<_, Option<String>>(9)?,
                    "content_type": row.get::<_, Option<String>>(10)?,
                    "initiator": row.get::<_, Option<String>>(11)?,
                    "duration_ms": row.get::<_, Option<i64>>(12)?,
                    "is_streaming": row.get::<_, i64>(13)? != 0,
                    "is_websocket": row.get::<_, i64>(14)? != 0,
                    "source": row.get::<_, String>(15)?,
                }))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(json!({"session": summary, "requests": requests}))
    }

    pub fn export_session_json(&self, session_id: &str, output: impl AsRef<Path>) -> Result<()> {
        let value = self.export_session_value(session_id)?;
        std::fs::write(output, serde_json::to_vec_pretty(&value)?)?;
        Ok(())
    }
}

fn next_sequence(tx: &Transaction<'_>, table: &str, session_id: &str) -> Result<i64> {
    let allowed = matches!(table, "requests" | "interaction_events");
    assert!(allowed, "untrusted sequence table");
    let sql = format!("SELECT COALESCE(MAX(sequence),0)+1 FROM {table} WHERE session_id=?1");
    Ok(tx.query_row(&sql, [session_id], |row| row.get(0))?)
}

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  name TEXT,
  target_url TEXT,
  status TEXT NOT NULL DEFAULT 'stopped',
  created_at INTEGER NOT NULL,
  stopped_at INTEGER
);
CREATE TABLE IF NOT EXISTS requests (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  timestamp INTEGER NOT NULL,
  method TEXT NOT NULL,
  url TEXT NOT NULL,
  request_headers TEXT,
  request_body TEXT,
  status_code INTEGER,
  response_headers TEXT,
  response_body TEXT,
  content_type TEXT,
  initiator TEXT,
  duration_ms INTEGER,
  is_streaming INTEGER DEFAULT 0,
  is_websocket INTEGER DEFAULT 0,
  source TEXT DEFAULT 'cdp'
);
CREATE TABLE IF NOT EXISTS js_hooks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  timestamp INTEGER NOT NULL,
  hook_type TEXT NOT NULL,
  function_name TEXT NOT NULL,
  arguments TEXT,
  result TEXT,
  call_stack TEXT,
  related_request_id TEXT
);
CREATE TABLE IF NOT EXISTS storage_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  timestamp INTEGER NOT NULL,
  domain TEXT NOT NULL,
  storage_type TEXT NOT NULL,
  data TEXT
);
CREATE TABLE IF NOT EXISTS analysis_reports (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  llm_provider TEXT NOT NULL,
  llm_model TEXT NOT NULL,
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  filter_prompt_tokens INTEGER,
  filter_completion_tokens INTEGER,
  report_content TEXT
);
CREATE TABLE IF NOT EXISTS fingerprint_profiles (
  session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
  profile_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS chat_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL REFERENCES analysis_reports(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS ai_request_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE,
  report_id TEXT REFERENCES analysis_reports(id) ON DELETE SET NULL,
  type TEXT NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  request_url TEXT NOT NULL,
  request_method TEXT NOT NULL DEFAULT 'POST',
  request_headers TEXT NOT NULL,
  request_body TEXT NOT NULL,
  status_code INTEGER,
  response_headers TEXT,
  response_body TEXT,
  prompt_tokens INTEGER DEFAULT 0,
  completion_tokens INTEGER DEFAULT 0,
  duration_ms INTEGER,
  error TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS interaction_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  type TEXT NOT NULL,
  timestamp INTEGER NOT NULL,
  x REAL,
  y REAL,
  selector TEXT,
  xpath TEXT,
  tag_name TEXT,
  element_text TEXT,
  input_value TEXT,
  key TEXT,
  scroll_x REAL,
  scroll_y REAL,
  url TEXT NOT NULL,
  page_title TEXT,
  path TEXT,
  created_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_requests_session ON requests(session_id, sequence);
CREATE INDEX IF NOT EXISTS idx_requests_url ON requests(url);
CREATE INDEX IF NOT EXISTS idx_js_hooks_session ON js_hooks(session_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_storage_session ON storage_snapshots(session_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_reports_session ON analysis_reports(session_id);
CREATE INDEX IF NOT EXISTS idx_interactions_session_seq ON interaction_events(session_id, sequence);
"#;

#[cfg(test)]
mod tests {
    use super::*;
    use aa_protocol::{unix_millis, InteractionEvent, RequestRecord};

    #[test]
    fn persists_and_exports_session() {
        let store = Store::open(":memory:").expect("open");
        store.migrate().expect("migrate");
        store
            .create_session("s1", "Test", "https://example.test", unix_millis())
            .expect("session");
        let mut request = RequestRecord::new("s1", "GET", "https://example.test/a");
        request.status_code = Some(200);
        request.response_body = Some("ok".into());
        request.source = "proxy".into();
        let stored = store.add_request(request).expect("request");
        assert_eq!(stored.sequence, 1);
        assert!(!stored.id.is_empty());
        store
            .add_interaction(InteractionEvent {
                session_id: "s1".into(),
                sequence: 0,
                event_type: "click".into(),
                timestamp: unix_millis(),
                x: Some(1.0),
                y: Some(2.0),
                selector: Some("button".into()),
                xpath: None,
                tag_name: Some("BUTTON".into()),
                element_text: Some("Go".into()),
                input_value: None,
                key: None,
                scroll_x: None,
                scroll_y: None,
                url: "https://example.test".into(),
                page_title: Some("Example".into()),
                path: None,
            })
            .expect("interaction");
        let summary = store.summary("s1").expect("summary");
        assert_eq!(summary.request_count, 1);
        assert_eq!(summary.interaction_count, 1);
        let exported = store.export_session_value("s1").expect("export");
        assert_eq!(exported["requests"][0]["status_code"], 200);
    }
}
