# OpenROAD 布局脚本

`openroad_resizer.tcl` 由 `openroad_resizer.py` 调用，读取标准单元与 SRAM 的 LEF、Liberty、
RC 配置、映射网表和目标 SDC。脚本建立 floorplan，完成全局布局和标准电气修复，并输出
修复网表、电气检查及 setup 时序报告。

目标周期、布局密度和每轮修复数量由调用方提供；时序评估使用同一组输入约束。
