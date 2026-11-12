//! `.cflg` 月度账本文件（格式 v2，与 Java 版 `LedgerIO` 逐字节兼容）。
//!
//! 布局（大端）：magic、version、year、month、size，随后按列存放 size 个
//! 时间戳(i64)、金额(i64，分)、类别号(i32)、子类号(i32)、备注偏移(i32)、备注长度(i32)，
//! 再是备注字节块（UTF-8）和分类树（名称用 Java `writeUTF` 的 modified UTF-8）。

use serde::{Deserialize, Serialize};
use std::path::Path;

const MAGIC: i32 = 0x4D4C_4432; // "MLD2"
const VERSION: i32 = 2;
/// 每行固定占用：2 个 i64 + 4 个 i32。
const ROW_BYTES: usize = 8 * 2 + 4 * 4;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Entry {
    /// 交易时间，epoch 毫秒
    pub ts: i64,
    /// 金额（分），正数收入，负数支出
    pub amount: i64,
    pub category: String,
    pub sub: String,
    pub note: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Category {
    pub name: String,
    pub subs: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Ledger {
    pub year: i32,
    pub month: i32,
    pub entries: Vec<Entry>,
    pub taxonomy: Vec<Category>,
}

/// 读取账本，记录按时间升序返回。
pub fn load(path: &Path) -> Result<Ledger, String> {
    let bytes = std::fs::read(path).map_err(|e| format!("读取失败：{e}"))?;
    let mut ledger = decode(&bytes)?;
    ledger.entries.sort_by_key(|e| e.ts);
    Ok(ledger)
}

/// 补全分类树后原子写入，返回补全后的分类树。
pub fn save(path: &Path, mut ledger: Ledger) -> Result<Vec<Category>, String> {
    complete_taxonomy(&mut ledger)?;
    let bytes = encode(&ledger)?;
    crate::write_atomic(path, &bytes)?;
    Ok(ledger.taxonomy)
}

/// 把记录里出现、分类树里没有的类别/子类追加到末尾，并检查分类树完整。
pub fn complete_taxonomy(ledger: &mut Ledger) -> Result<(), String> {
    for e in &ledger.entries {
        if e.category.trim().is_empty() || e.sub.trim().is_empty() {
            return Err("存在未填写类别或子类别的记录".into());
        }
        let cat = match ledger.taxonomy.iter().position(|c| c.name == e.category) {
            Some(i) => &mut ledger.taxonomy[i],
            None => {
                ledger.taxonomy.push(Category {
                    name: e.category.clone(),
                    subs: Vec::new(),
                });
                ledger.taxonomy.last_mut().unwrap()
            }
        };
        if !cat.subs.contains(&e.sub) {
            cat.subs.push(e.sub.clone());
        }
    }
    if ledger.taxonomy.is_empty() {
        return Err("至少需要一个类别".into());
    }
    if let Some(c) = ledger.taxonomy.iter().find(|c| c.subs.is_empty()) {
        return Err(format!("类别【{}】至少需要一个子类别", c.name));
    }
    Ok(())
}

pub fn encode(ledger: &Ledger) -> Result<Vec<u8>, String> {
    if !(1..=12).contains(&ledger.month) {
        return Err(format!("非法月份：{}", ledger.month));
    }
    let n = ledger.entries.len();
    let size = i32::try_from(n).map_err(|_| "记录过多".to_string())?;
    let mut ids = Vec::with_capacity(n);
    for e in &ledger.entries {
        let c = ledger
            .taxonomy
            .iter()
            .position(|c| c.name == e.category)
            .ok_or_else(|| format!("分类树中缺少类别【{}】", e.category))?;
        let s = ledger.taxonomy[c]
            .subs
            .iter()
            .position(|s| *s == e.sub)
            .ok_or_else(|| format!("类别【{}】中缺少子类别【{}】", e.category, e.sub))?;
        ids.push((c as i32, s as i32));
    }

    let mut blob = Vec::new();
    let mut spans = Vec::with_capacity(n);
    for e in &ledger.entries {
        spans.push((blob.len() as i32, e.note.len() as i32));
        blob.extend_from_slice(e.note.as_bytes());
    }
    let blob_len = i32::try_from(blob.len()).map_err(|_| "备注总长度过大".to_string())?;

    let mut out = Vec::with_capacity(20 + n * ROW_BYTES + blob.len() + 256);
    for v in [MAGIC, VERSION, ledger.year, ledger.month, size] {
        out.extend_from_slice(&v.to_be_bytes());
    }
    ledger
        .entries
        .iter()
        .for_each(|e| out.extend_from_slice(&e.ts.to_be_bytes()));
    ledger
        .entries
        .iter()
        .for_each(|e| out.extend_from_slice(&e.amount.to_be_bytes()));
    ids.iter()
        .for_each(|(c, _)| out.extend_from_slice(&c.to_be_bytes()));
    ids.iter()
        .for_each(|(_, s)| out.extend_from_slice(&s.to_be_bytes()));
    spans
        .iter()
        .for_each(|(off, _)| out.extend_from_slice(&off.to_be_bytes()));
    spans
        .iter()
        .for_each(|(_, len)| out.extend_from_slice(&len.to_be_bytes()));
    out.extend_from_slice(&blob_len.to_be_bytes());
    out.extend_from_slice(&blob);

    if ledger.taxonomy.is_empty() {
        return Err("至少需要一个类别".into());
    }
    out.extend_from_slice(&(ledger.taxonomy.len() as i32).to_be_bytes());
    for c in &ledger.taxonomy {
        write_java_utf(&mut out, &c.name)?;
        if c.subs.is_empty() {
            return Err(format!("类别【{}】至少需要一个子类别", c.name));
        }
        out.extend_from_slice(&(c.subs.len() as i32).to_be_bytes());
        for s in &c.subs {
            write_java_utf(&mut out, s)?;
        }
    }
    Ok(out)
}

pub fn decode(bytes: &[u8]) -> Result<Ledger, String> {
    let mut r = Reader { buf: bytes, pos: 0 };
    if r.i32()? != MAGIC {
        return Err("非法文件头".into());
    }
    let ver = r.i32()?;
    if ver != VERSION {
        return Err(format!("不支持的版本: {ver}"));
    }
    let year = r.i32()?;
    let month = r.i32()?;
    if !(1..=12).contains(&month) {
        return Err(format!("文件已损坏：非法月份 {month}"));
    }
    let n = usize::try_from(r.i32()?).map_err(|_| "文件已损坏：记录数为负".to_string())?;
    // 先按剩余长度校验记录数，避免损坏文件触发超大分配。
    if n.checked_mul(ROW_BYTES)
        .is_none_or(|need| need > r.remaining())
    {
        return Err("文件已损坏：记录数超出文件长度".into());
    }
    let ts: Vec<i64> = (0..n).map(|_| r.i64()).collect::<Result<_, _>>()?;
    let amount: Vec<i64> = (0..n).map(|_| r.i64()).collect::<Result<_, _>>()?;
    let cat: Vec<i32> = (0..n).map(|_| r.i32()).collect::<Result<_, _>>()?;
    let sub: Vec<i32> = (0..n).map(|_| r.i32()).collect::<Result<_, _>>()?;
    let off: Vec<i32> = (0..n).map(|_| r.i32()).collect::<Result<_, _>>()?;
    let len: Vec<i32> = (0..n).map(|_| r.i32()).collect::<Result<_, _>>()?;
    let blob_len = usize::try_from(r.i32()?).map_err(|_| "文件已损坏：备注长度为负".to_string())?;
    let blob = r.take(blob_len)?;

    let cat_count = r.i32()?;
    if cat_count <= 0 {
        return Err("文件中未包含任何类别".into());
    }
    let mut taxonomy = Vec::new();
    for _ in 0..cat_count {
        let name = r.java_utf()?;
        let sub_count = r.i32()?;
        if sub_count <= 0 {
            return Err(format!("类别【{name}】没有子类别"));
        }
        let subs = (0..sub_count)
            .map(|_| r.java_utf())
            .collect::<Result<_, _>>()?;
        taxonomy.push(Category { name, subs });
    }

    let mut entries = Vec::with_capacity(n);
    for i in 0..n {
        let c = usize::try_from(cat[i])
            .ok()
            .and_then(|c| taxonomy.get(c))
            .ok_or_else(|| format!("文件已损坏：第 {} 条记录的类别号 {} 无效", i + 1, cat[i]))?;
        let s = usize::try_from(sub[i])
            .ok()
            .and_then(|s| c.subs.get(s))
            .ok_or_else(|| format!("文件已损坏：第 {} 条记录的子类号 {} 无效", i + 1, sub[i]))?;
        let note = match (usize::try_from(off[i]), usize::try_from(len[i])) {
            (Ok(o), Ok(l)) if o.checked_add(l).is_some_and(|end| end <= blob.len()) => {
                String::from_utf8_lossy(&blob[o..o + l]).into_owned()
            }
            _ => return Err(format!("文件已损坏：第 {} 条记录的备注位置无效", i + 1)),
        };
        entries.push(Entry {
            ts: ts[i],
            amount: amount[i],
            category: c.name.clone(),
            sub: s.clone(),
            note,
        });
    }
    Ok(Ledger {
        year,
        month,
        entries,
        taxonomy,
    })
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        if n > self.remaining() {
            return Err("文件已损坏：内容被截断".into());
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    fn i32(&mut self) -> Result<i32, String> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }

    fn i64(&mut self) -> Result<i64, String> {
        Ok(i64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }

    /// Java `DataInputStream.readUTF`：u16 长度 + modified UTF-8。
    fn java_utf(&mut self) -> Result<String, String> {
        let n = u16::from_be_bytes(self.take(2)?.try_into().unwrap()) as usize;
        let bytes = self.take(n)?;
        let bad = || "文件已损坏：分类名编码无效".to_string();
        let cont = |i: usize| match bytes.get(i) {
            Some(b) if b & 0xC0 == 0x80 => Ok((b & 0x3F) as u16),
            _ => Err(bad()),
        };
        let mut units = Vec::with_capacity(n);
        let mut i = 0;
        while i < n {
            let b = bytes[i] as u16;
            if b & 0x80 == 0 {
                units.push(b);
                i += 1;
            } else if b & 0xE0 == 0xC0 {
                units.push((b & 0x1F) << 6 | cont(i + 1)?);
                i += 2;
            } else if b & 0xF0 == 0xE0 {
                units.push((b & 0x0F) << 12 | cont(i + 1)? << 6 | cont(i + 2)?);
                i += 3;
            } else {
                return Err(bad());
            }
        }
        // 增补字符在 Java 里是两个代理项，这里按 UTF-16 合并。
        Ok(String::from_utf16_lossy(&units))
    }
}

/// Java `DataOutputStream.writeUTF`：U+0000 写成 C0 80，增补字符按代理项各写 3 字节。
fn write_java_utf(out: &mut Vec<u8>, s: &str) -> Result<(), String> {
    let mut bytes = Vec::with_capacity(s.len());
    for u in s.encode_utf16() {
        match u {
            0x01..=0x7F => bytes.push(u as u8),
            0x00 | 0x80..=0x7FF => {
                bytes.push(0xC0 | (u >> 6) as u8);
                bytes.push(0x80 | (u & 0x3F) as u8);
            }
            _ => {
                bytes.push(0xE0 | (u >> 12) as u8);
                bytes.push(0x80 | ((u >> 6) & 0x3F) as u8);
                bytes.push(0x80 | (u & 0x3F) as u8);
            }
        }
    }
    let len = u16::try_from(bytes.len()).map_err(|_| format!("分类名过长：{s}"))?;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(&bytes);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const JAVA_FIXTURE: &[u8] = include_bytes!("../tests/fixtures/java-v2.cflg");

    /// 与 fixture 生成脚本相同的内容（Java 原版 LedgerIO 写出）。
    fn fixture_ledger() -> Ledger {
        let e = |ts, amount, category: &str, sub: &str, note: &str| Entry {
            ts,
            amount,
            category: category.into(),
            sub: sub.into(),
            note: note.into(),
        };
        Ledger {
            year: 2025,
            month: 9,
            entries: vec![
                e(1756656000000, -2500, "餐饮", "正餐", "午饭：米线"),
                e(1756659600000, 150000, "快餐🍔", "a\0b", ""),
                e(1756655999000, -1200, "快餐🍔", "汉堡", "汉堡🍔 x2"),
            ],
            taxonomy: vec![
                Category {
                    name: "餐饮".into(),
                    subs: vec!["正餐".into(), "小吃".into()],
                },
                Category {
                    name: "快餐🍔".into(),
                    subs: vec!["汉堡".into(), "a\0b".into()],
                },
            ],
        }
    }

    #[test]
    fn reads_file_written_by_java() {
        assert_eq!(decode(JAVA_FIXTURE).unwrap(), fixture_ledger());
    }

    #[test]
    fn writes_bytes_identical_to_java() {
        assert_eq!(encode(&fixture_ledger()).unwrap(), JAVA_FIXTURE);
    }

    #[test]
    fn load_sorts_by_time_and_save_round_trips() {
        let dir = std::env::temp_dir().join(format!("cashflow-ledger-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("a.cflg");
        std::fs::write(&path, JAVA_FIXTURE).unwrap();
        let loaded = load(&path).unwrap();
        let ts: Vec<i64> = loaded.entries.iter().map(|e| e.ts).collect();
        assert_eq!(ts, [1756655999000, 1756656000000, 1756659600000]);

        let mut edited = loaded.clone();
        edited.entries.push(Entry {
            ts: 1756660000000,
            amount: -300,
            category: "交通".into(),
            sub: "地铁".into(),
            note: String::new(),
        });
        let tax = save(&path, edited).unwrap();
        assert_eq!(tax.last().unwrap().name, "交通");
        assert!(!dir.join("a.cflg.tmp").exists());
        assert_eq!(load(&path).unwrap().entries.len(), 4);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn corrupted_files_are_rejected_without_panic() {
        for cut in 0..JAVA_FIXTURE.len() {
            assert!(
                decode(&JAVA_FIXTURE[..cut]).is_err(),
                "截断到 {cut} 字节应报错"
            );
        }
        // 记录数改成极大值：应按长度拒绝，而不是尝试分配
        let mut huge = JAVA_FIXTURE.to_vec();
        huge[16..20].copy_from_slice(&i32::MAX.to_be_bytes());
        assert!(decode(&huge).unwrap_err().contains("记录数"));
        // 第一条记录的类别号越界
        let mut bad_cat = JAVA_FIXTURE.to_vec();
        let cat_col = 20 + 3 * 16;
        bad_cat[cat_col..cat_col + 4].copy_from_slice(&7i32.to_be_bytes());
        assert!(decode(&bad_cat).unwrap_err().contains("类别号"));
        // 备注越界
        let mut bad_note = JAVA_FIXTURE.to_vec();
        let len_col = 20 + 3 * 16 + 3 * 4 * 3;
        bad_note[len_col..len_col + 4].copy_from_slice(&999i32.to_be_bytes());
        assert!(decode(&bad_note).unwrap_err().contains("备注"));
    }

    #[test]
    fn save_rejects_incomplete_rows() {
        let mut l = fixture_ledger();
        l.entries[0].sub = " ".into();
        assert!(complete_taxonomy(&mut l).is_err());
        let mut empty = fixture_ledger();
        empty.entries.clear();
        empty.taxonomy.clear();
        assert!(complete_taxonomy(&mut empty).is_err());
    }
}
