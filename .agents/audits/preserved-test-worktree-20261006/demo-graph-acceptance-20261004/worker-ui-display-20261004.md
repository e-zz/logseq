# UI 显示与引用跳转：PASS_PENDING_PARENT_REVIEW

## 范围
使用现有原生 cua-driver 后台操作 Logseq pid=25932/window_id=201312；未安装、未改源码/配置、未重启、未执行 upsertNodes。当前图 Demo，UIA 文档 graph-id=00000000-4b4f-4f7f-a653-0eb2fc3812ce。页面 UUID=63bab91a-013d-44df-afb7-4c83026b03ef，metadata UUID=d8d5836c-5a3c-4fe8-8258-f0edd7d3c706。

## 真实证据
本目录 ui-08-current-search.png、ui-10-test-page.png、ui-12-expanded.png、ui-14-returned.png、ui-16-expanded-confirm.png；helper 按每次动作保存输入、stdout、stderr、退出码与实际时间，文件前缀 08-current-search 至 16-expanded-confirm。执行 helper 为 C:/Users/zhang/AppData/Local/hermes/cache/scratch/logseq-cua-run.py。

## 实测：截图确认
- ui-10-test-page.png：测试页标题正确；编号项一/二/三显示真实编号 1/2/3；父→子→孙逐级缩进；专用类型属性任务测试显示完成勾选与 #Task。MCP getPage 同时读回 Task status.done 与嵌套 UUID，编号 order-list-type=number。
- 点击 11-expand-topics 时误选引用而不是 Expand，未记为展开成功。ui-12-expanded.png 和 UIA 文档 URL 的 page/70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda 验证父块引用跳转，子/孙仍显示。此动作是导航而非数据编辑。
- 13-return-test-page 点击 Recent 中测试页返回；ui-14-returned.png 确认。
- 15-expand-coordinate 点击截图坐标 x=1290,y=600；ui-16-expanded-confirm.png 显示 Collapse，目标块 Topics 三个引用全部可见，Number=0，Checkbox未勾选，CV=https://example.org/demo-cv-updated，ORCID=TEST-ORCID-NOT-A-REAL-ID，Description=专用类型属性真实赋值测试。
- 页顶部属性是 CV=https://example.org/demo-page、Number=7、Checkbox未勾选；与目标块属性明确区分。
- MCP getBlock 在本次 UI 测试中只读确认目标块 updated-at=1791080520810，Number=0，Checkbox=false，Topics引用 UUID依次为70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda、e6a5a455-812d-4672-ae8f-7c0a5e3633c0、d00c2214-fa31-44af-bbea-7f5f9a7c45b9。

## 不能外推
驱动 click 返回 effect=unverifiable，故每次以随后截图/UIA实际变化核验，不以调用退出码代表成功。仅验证静态显示、Expand及一个引用跳转；未验证 UI 改Task状态后MCP双向读回、编号插入/移动/缩进/bullet切换、正常关闭重开持久性。未正式关单，交主 agent 独立读取原始动作响应和截图复核。
