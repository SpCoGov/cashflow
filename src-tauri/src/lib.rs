mod analysis;
mod ledger;
mod money;
mod rules;
mod wechat;

use analysis::Analysis;
use chrono::{Local, TimeZone};
use ledger::{Category, Entry, Ledger};
use rules::RuleConfig;
use std::io::Write;
use std::path::{Path, PathBuf};

/// 先写同目录临时文件再替换，避免写到一半时损坏原文件。
pub(crate) fn write_atomic(path: &Path, data: &[u8]) -> Result<(), String> {
    let mut tmp = path.as_os_str().to_owned();
    tmp.push(".tmp");
    let tmp = PathBuf::from(tmp);
    let written = std::fs::File::create(&tmp).and_then(|mut f| {
        f.write_all(data)?;
        f.sync_all()
    });
    if let Err(e) = written.and_then(|_| std::fs::rename(&tmp, path)) {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("写入 {} 失败：{e}", path.display()));
    }
    Ok(())
}

#[tauri::command(async)]
fn open_ledger(path: String) -> Result<Ledger, String> {
    ledger::load(Path::new(&path))
}

/// 返回补全后的分类树（记录中出现的新类别/子类会追加进去）。
#[tauri::command(async)]
fn save_ledger(path: String, ledger: Ledger) -> Result<Vec<Category>, String> {
    ledger::save(Path::new(&path), ledger)
}

/// 解析账单并套用规则。`last_ts` 是账本里最新记录的时间，规则开启
/// onlyAppendFromLastDate 时只保留该日 0 点之后的交易。
#[tauri::command(async)]
fn import_bill(
    path: String,
    rules_path: Option<String>,
    last_ts: Option<i64>,
) -> Result<Vec<Entry>, String> {
    let cfg = match rules_path {
        Some(p) => rules::load(Path::new(&p))?,
        None => RuleConfig::default(),
    };
    let raw = wechat::parse_file(Path::new(&path))?;
    let mut out = rules::apply(&raw, &cfg)?;
    if let (true, Some(last)) = (cfg.defaults.only_append_from_last_date, last_ts) {
        let cutoff = start_of_local_day(last)?;
        out.retain(|e| e.ts >= cutoff);
    }
    Ok(out)
}

fn start_of_local_day(ts: i64) -> Result<i64, String> {
    let day = Local
        .timestamp_millis_opt(ts)
        .single()
        .ok_or("无效的时间戳")?
        .date_naive();
    Ok(Local
        .from_local_datetime(&day.and_hms_opt(0, 0, 0).unwrap())
        .earliest()
        .ok_or("本地时区不存在该日 0 点")?
        .timestamp_millis())
}

#[tauri::command(async)]
fn load_rules(path: String) -> Result<RuleConfig, String> {
    rules::load(Path::new(&path))
}

#[tauri::command(async)]
fn save_rules(path: String, config: RuleConfig) -> Result<(), String> {
    rules::save(Path::new(&path), config)
}

#[tauri::command(async)]
fn analyze(entries: Vec<Entry>) -> Analysis {
    analysis::analyze(entries)
}

#[tauri::command(async)]
fn export_analysis(path: String, analysis: Analysis) -> Result<(), String> {
    analysis::export_xlsx(Path::new(&path), &analysis)
}

/// 通过文件关联或命令行打开时传入的账本路径。
#[tauri::command]
fn startup_path() -> Option<String> {
    std::env::args().nth(1)
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            open_ledger,
            save_ledger,
            import_bill,
            load_rules,
            save_rules,
            analyze,
            export_analysis,
            startup_path
        ])
        .run(tauri::generate_context!())
        .expect("启动 Cashflow 失败");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cutoff_is_local_midnight_of_last_record() {
        let ndt = chrono::NaiveDate::from_ymd_opt(2025, 9, 10)
            .unwrap()
            .and_hms_opt(15, 30, 0)
            .unwrap();
        let ts = Local.from_local_datetime(&ndt).unwrap().timestamp_millis();
        let midnight = Local
            .from_local_datetime(&ndt.date().and_hms_opt(0, 0, 0).unwrap())
            .unwrap()
            .timestamp_millis();
        assert_eq!(start_of_local_day(ts).unwrap(), midnight);
    }

    #[test]
    fn atomic_write_replaces_existing_file() {
        let path = std::env::temp_dir().join(format!("cashflow-atomic-{}.bin", std::process::id()));
        write_atomic(&path, b"old").unwrap();
        write_atomic(&path, b"new").unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"new");
        std::fs::remove_file(&path).unwrap();
    }
}
