# coding: utf-8

"""跑存量 oracle 时需要的我方补件。

**为什么单列一个顶层包**：这些件是我方写的，不是存量的。此前它们被放进
`applications/a2a_service/common/`——那是差分取真值的地方，我方的东西混进去，
「存量真值」就不再是存量。本仓已不留 oracle 副本（见 `tools/legacy_oracle.sh`），
oracle 每次按锚定提交临时导出且只读，我方补件因此必须有自己的位置。

**不叫 `common`**：叫 `common` 会与 oracle 导出的同名包撞名，
先被找到的那个胜出，而胜出方取决于 `sys.path` 顺序——读数不可判定。
"""
