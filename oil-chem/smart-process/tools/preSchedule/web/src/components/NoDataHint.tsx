"use client";

export function NoDataHint() {
  return (
    <div className="text-center py-20">
      <div className="text-amber-400 text-lg mb-2">{"\u26a0 \u8fd8\u6ca1\u6709\u6392\u4ea7\u6570\u636e"}</div>
      <div className="text-slate-400 text-sm">
        {"\u8bf7\u70b9\u51fb\u9875\u9762\u53f3\u4e0a\u89d2\u7684"}
        <span className="text-green-400 font-medium">{" \u25b6 \u8fd0\u884c\u6392\u4ea7 "}</span>
        {"\u6309\u94ae\u542f\u52a8 CP-SAT \u6392\u4ea7\uff0c\u9884\u8ba1\u8017\u65f6 4-5 \u5206\u949f"}
      </div>
    </div>
  );
}

export default NoDataHint;
