import { useState } from "react";
import type { Category } from "./api";
import { Modal, useDialogs } from "./dialogs";

export type RenameOp = { kind: "category"; from: string; to: string } | { kind: "sub"; category: string; from: string; to: string };

/** 按顺序把重命名应用到 [类别, 子类] 对上。 */
export function applyRename<T extends { category: string; sub: string }>(rows: T[], op: RenameOp): T[] {
  return rows.map((r) => {
    if (op.kind === "category") return r.category === op.from ? { ...r, category: op.to } : r;
    return r.category === op.category && r.sub === op.from ? { ...r, sub: op.to } : r;
  });
}

type Props = {
  taxonomy: Category[];
  /** 当前账本里用到的 [类别, 子类]，用于禁止删除被引用的分类 */
  used: { category: string; sub: string }[];
  onOk: (taxonomy: Category[], ops: RenameOp[]) => void;
  onCancel: () => void;
};

export function CategoryEditor({ taxonomy, used: initialUsed, onOk, onCancel }: Props) {
  const dialogs = useDialogs();
  const [cats, setCats] = useState<Category[]>(taxonomy);
  const [ops, setOps] = useState<RenameOp[]>([]);
  // 已应用本次重命名的引用列表，删除检查以它为准
  const [used, setUsed] = useState(initialUsed);
  const [ci, setCi] = useState(taxonomy.length ? 0 : -1);
  const [si, setSi] = useState(-1);
  const cur = cats[ci];

  const ask = async (title: string, message: string, initial = "") => {
    const v = await dialogs.prompt(title, message, initial);
    if (v === null) return null;
    if (!v) {
      await dialogs.alert("名称不能为空");
      return null;
    }
    return v;
  };
  const record = (op: RenameOp) => {
    setOps((o) => [...o, op]);
    setUsed((u) => applyRename(u, op));
  };

  const addCat = async () => {
    const name = await ask("新增类别", "输入类别名称：");
    if (!name) return;
    if (cats.some((c) => c.name === name)) return dialogs.alert("类别已存在");
    const sub = await ask("新增子类", "为该类别添加至少一个子类：");
    if (!sub) return;
    setCats([...cats, { name, subs: [sub] }]);
    setCi(cats.length);
    setSi(0);
  };
  const renameCat = async () => {
    if (!cur) return dialogs.alert("请先选择一个类别");
    const name = await ask("重命名类别", "输入新的类别名称：", cur.name);
    if (!name || name === cur.name) return;
    if (cats.some((c) => c.name === name)) return dialogs.alert("类别已存在");
    setCats(cats.map((c, i) => (i === ci ? { ...c, name } : c)));
    record({ kind: "category", from: cur.name, to: name });
  };
  const deleteCat = async () => {
    if (!cur) return dialogs.alert("请先选择一个类别");
    if (cats.length <= 1) return dialogs.alert("至少需要保留一个类别，不能删除最后一个");
    if (used.some((u) => u.category === cur.name)) return dialogs.alert(`类别【${cur.name}】被记录引用，不能删除`);
    setCats(cats.filter((_, i) => i !== ci));
    setCi(Math.min(ci, cats.length - 2));
    setSi(-1);
  };
  const setSubs = (subs: string[]) => setCats(cats.map((c, i) => (i === ci ? { ...c, subs } : c)));
  const addSub = async () => {
    if (!cur) return dialogs.alert("请先选择一个类别");
    const name = await ask("新增子类", "输入子类名称：");
    if (!name) return;
    if (cur.subs.includes(name)) return dialogs.alert("子类已存在");
    setSubs([...cur.subs, name]);
    setSi(cur.subs.length);
  };
  const renameSub = async () => {
    if (!cur) return dialogs.alert("请先选择一个类别");
    const old = cur.subs[si];
    if (old === undefined) return dialogs.alert("请先选择一个子类");
    const name = await ask("重命名子类", "输入新的子类名称：", old);
    if (!name || name === old) return;
    if (cur.subs.includes(name)) return dialogs.alert("子类已存在");
    setSubs(cur.subs.map((s, i) => (i === si ? name : s)));
    record({ kind: "sub", category: cur.name, from: old, to: name });
  };
  const deleteSub = async () => {
    if (!cur) return dialogs.alert("请先选择一个类别");
    const sub = cur.subs[si];
    if (sub === undefined) return dialogs.alert("请先选择一个子类");
    if (cur.subs.length <= 1) return dialogs.alert("每个类别至少需要一个子类，不能删除最后一个");
    if (used.some((u) => u.category === cur.name && u.sub === sub)) return dialogs.alert(`【${cur.name} / ${sub}】被记录引用，不能删除`);
    setSubs(cur.subs.filter((_, i) => i !== si));
    setSi(Math.min(si, cur.subs.length - 2));
  };

  const ok = () => {
    if (!cats.length) return dialogs.alert("至少需要一个类别");
    const empty = cats.find((c) => !c.subs.length);
    if (empty) return dialogs.alert(`类别【${empty.name}】缺少子类`);
    onOk(cats, ops);
  };

  return (
    <Modal
      title="编辑分类"
      onClose={onCancel}
      footer={
        <>
          <button onClick={onCancel}>取消</button>
          <button className="primary" onClick={ok}>
            确定
          </button>
        </>
      }
    >
      <div className="category-editor">
        <section>
          <h3>类别</h3>
          <ul className="pick-list" role="listbox" aria-label="类别">
            {cats.map((c, i) => (
              <li key={c.name} role="option" aria-selected={i === ci}>
                <button
                  className={i === ci ? "selected" : undefined}
                  onClick={() => {
                    setCi(i);
                    setSi(-1);
                  }}
                >
                  {c.name}
                </button>
              </li>
            ))}
          </ul>
          <div className="row-buttons">
            <button onClick={addCat}>新增</button>
            <button onClick={renameCat}>重命名</button>
            <button onClick={deleteCat}>删除</button>
          </div>
        </section>
        <section>
          <h3>子类{cur ? `（${cur.name}）` : ""}</h3>
          <ul className="pick-list" role="listbox" aria-label="子类">
            {cur?.subs.map((s, i) => (
              <li key={s} role="option" aria-selected={i === si}>
                <button className={i === si ? "selected" : undefined} onClick={() => setSi(i)}>
                  {s}
                </button>
              </li>
            ))}
          </ul>
          <div className="row-buttons">
            <button onClick={addSub}>新增</button>
            <button onClick={renameSub}>重命名</button>
            <button onClick={deleteSub}>删除</button>
          </div>
        </section>
      </div>
    </Modal>
  );
}
