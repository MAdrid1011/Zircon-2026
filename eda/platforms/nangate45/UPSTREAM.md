# 上游来源

Nangate Open Cell Library 是用于研究、测试和探索 EDA 流程的开放标准单元参考库，不面向制造。

- 版本：`PDKv1.3_v2010_12.Apache.CCL`
- 原始发布地址：https://projects.si2.org/openeda.si2.org/project/showfiles.php?group_id=63#503
- 本仓库采用的 OpenROAD-flow-scripts 版本与文件校验值见 [平台说明](README.md)和
  [platform.json](platform.json)。

上游 OpenROAD 平台为 LEF 生成抽象视图，使引脚几何不依赖 GDS 多边形表示；同时补充流程所需
文件、修正 `AOI21_X1` 单元的接触孔包围规则，并保留原始许可文件。
