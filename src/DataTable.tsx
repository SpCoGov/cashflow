import { useState, type ReactNode } from "react";

export type Column<T> = {
  key: string;
  title: string;
  /** 排序依据 */
  value: (row: T) => string | number;
  render?: (row: T) => ReactNode;
  align?: "right";
  width?: number;
};

export type Sort = { key: string; dir: 1 | -1 } | null;

export const useSort = (initial: Sort = null) => useState<Sort>(initial);

export function sortRows<T>(rows: T[], columns: Column<T>[], sort: Sort): T[] {
  const col = sort && columns.find((c) => c.key === sort.key);
  if (!sort || !col) return rows;
  return [...rows].sort((a, b) => {
    const x = col.value(a);
    const y = col.value(b);
    const c = typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y), "zh-CN");
    return c * sort.dir;
  });
}

type Props<T> = {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  sort: Sort;
  onSort: (s: Sort) => void;
  selected?: string | number | null;
  onSelect?: (row: T) => void;
  onActivate?: (row: T) => void;
  empty?: ReactNode;
};

/** 点击表头排序（升序 → 降序）；单击选中，双击触发 onActivate。 */
export function DataTable<T>({ columns, rows, rowKey, sort, onSort, selected, onSelect, onActivate, empty }: Props<T>) {
  const sorted = sortRows(rows, columns, sort);
  return (
    <table className="grid">
      <thead>
        <tr>
          {columns.map((c) => {
            const active = sort?.key === c.key;
            return (
              <th
                key={c.key}
                style={{ width: c.width }}
                className={c.align === "right" ? "num" : undefined}
                aria-sort={active ? (sort.dir === 1 ? "ascending" : "descending") : undefined}
                onClick={() => onSort({ key: c.key, dir: active && sort.dir === 1 ? -1 : 1 })}
              >
                {c.title}
                <span className="sort-mark">{active ? (sort.dir === 1 ? "▲" : "▼") : ""}</span>
              </th>
            );
          })}
        </tr>
      </thead>
      <tbody>
        {sorted.map((r) => {
          const k = rowKey(r);
          return (
            <tr
              key={k}
              className={k === selected ? "selected" : undefined}
              onClick={() => onSelect?.(r)}
              onDoubleClick={() => onActivate?.(r)}
            >
              {columns.map((c) => (
                <td key={c.key} className={c.align === "right" ? "num" : undefined}>
                  {c.render ? c.render(r) : c.value(r)}
                </td>
              ))}
            </tr>
          );
        })}
        {sorted.length === 0 && empty && (
          <tr className="empty">
            <td colSpan={columns.length}>{empty}</td>
          </tr>
        )}
      </tbody>
    </table>
  );
}
