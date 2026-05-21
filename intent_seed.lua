-- intent_seed.lua
-- 用法：
-- redis-cli -h 127.0.0.1 -p 6379 --eval .\intent_seed.lua
--
-- 当前统一后的设计：
-- 1) name 唯一
-- 2) nodeId 可重复
-- 3) Redis 直接使用 value 存完整 JSON：
--    intent:tree:node:{name} -> JSON
-- 4) children 用 Set 存：
--    intent:tree:children:{parentName} -> childName

local NODE_PREFIX = "intent:tree:node:"
local CHILDREN_PREFIX = "intent:tree:children:"

local function put(node)
    if not node.name or node.name == "" then
        return
    end

    -- 直接按 name 存完整 JSON
    redis.call("SET", NODE_PREFIX .. node.name, cjson.encode(node))

    -- parentName -> childName
    if node.parentName and node.parentName ~= "" then
        redis.call("SADD", CHILDREN_PREFIX .. node.parentName, node.name)
    end
end

local data = {
    -- 问候
    {name="问候", nodeId="聊天-问候", parentName=nil, description="问候总入口", topK=nil, childrenCount=9},
    {name="你好", nodeId="聊天-问候", parentName="问候", description="你好类问候", topK=5, childrenCount=0},
    {name="早上好", nodeId="聊天-问候", parentName="问候", description="早晨问候", topK=5, childrenCount=0},
    {name="下午好", nodeId="聊天-问候", parentName="问候", description="下午问候", topK=5, childrenCount=0},
    {name="晚上好", nodeId="聊天-问候", parentName="问候", description="晚上问候", topK=5, childrenCount=0},
    {name="在吗", nodeId="聊天-问候", parentName="问候", description="在线询问", topK=5, childrenCount=0},
    {name="你是谁", nodeId="聊天-问候", parentName="问候", description="身份询问", topK=5, childrenCount=0},
    {name="谢谢", nodeId="聊天-问候", parentName="问候", description="感谢表达", topK=5, childrenCount=0},
    {name="再见", nodeId="聊天-问候", parentName="问候", description="告别表达", topK=5, childrenCount=0},
    {name="辛苦了", nodeId="聊天-问候", parentName="问候", description="礼貌问候", topK=5, childrenCount=0},

    -- 请假
    {name="请假", nodeId="人事-请假", parentName=nil, description="请假总入口", topK=nil, childrenCount=9},
    {name="年假", nodeId="人事-请假", parentName="请假", description="年假相关", topK=8, childrenCount=0},
    {name="病假", nodeId="人事-请假", parentName="请假", description="病假相关", topK=8, childrenCount=0},
    {name="事假", nodeId="人事-请假", parentName="请假", description="事假相关", topK=8, childrenCount=0},
    {name="调休", nodeId="人事-请假", parentName="请假", description="调休相关", topK=8, childrenCount=0},
    {name="婚假", nodeId="人事-请假", parentName="请假", description="婚假相关", topK=8, childrenCount=0},
    {name="产假", nodeId="人事-请假", parentName="请假", description="产假相关", topK=8, childrenCount=0},
    {name="陪产假", nodeId="人事-请假", parentName="请假", description="陪产假相关", topK=8, childrenCount=0},
    {name="丧假", nodeId="人事-请假", parentName="请假", description="丧假相关", topK=8, childrenCount=0},
    {name="远程办公", nodeId="人事-请假", parentName="请假", description="远程办公相关", topK=8, childrenCount=0},

    -- 报销
    {name="报销", nodeId="财务-报销", parentName=nil, description="报销总入口", topK=nil, childrenCount=9},
    {name="交通报销", nodeId="财务-报销", parentName="报销", description="交通报销相关", topK=8, childrenCount=0},
    {name="差旅报销", nodeId="财务-报销", parentName="报销", description="差旅报销相关", topK=8, childrenCount=0},
    {name="餐补报销", nodeId="财务-报销", parentName="报销", description="餐补报销相关", topK=8, childrenCount=0},
    {name="招待报销", nodeId="财务-报销", parentName="报销", description="招待报销相关", topK=8, childrenCount=0},
    {name="办公报销", nodeId="财务-报销", parentName="报销", description="办公报销相关", topK=8, childrenCount=0},
    {name="培训报销", nodeId="财务-报销", parentName="报销", description="培训报销相关", topK=8, childrenCount=0},
    {name="住宿报销", nodeId="财务-报销", parentName="报销", description="住宿报销相关", topK=8, childrenCount=0},
    {name="发票", nodeId="财务-报销", parentName="报销", description="发票相关", topK=8, childrenCount=0},
    {name="打款时间", nodeId="财务-报销", parentName="报销", description="打款时间相关", topK=8, childrenCount=0},

    -- 工资
    {name="工资", nodeId="人事-工资", parentName=nil, description="工资总入口", topK=nil, childrenCount=9},
    {name="工资条", nodeId="人事-工资", parentName="工资", description="工资条相关", topK=8, childrenCount=0},
    {name="发薪日", nodeId="人事-工资", parentName="工资", description="发薪日相关", topK=8, childrenCount=0},
    {name="奖金", nodeId="人事-工资", parentName="工资", description="奖金相关", topK=8, childrenCount=0},
    {name="个税", nodeId="人事-工资", parentName="工资", description="个税相关", topK=8, childrenCount=0},
    {name="社保", nodeId="人事-工资", parentName="工资", description="社保相关", topK=8, childrenCount=0},
    {name="公积金", nodeId="人事-工资", parentName="工资", description="公积金相关", topK=8, childrenCount=0},
    {name="加班费", nodeId="人事-工资", parentName="工资", description="加班费相关", topK=8, childrenCount=0},
    {name="调薪", nodeId="人事-工资", parentName="工资", description="调薪相关", topK=8, childrenCount=0},
    {name="年终奖", nodeId="人事-工资", parentName="工资", description="年终奖相关", topK=8, childrenCount=0},

    -- 招聘
    {name="招聘", nodeId="人事-招聘", parentName=nil, description="招聘总入口", topK=nil, childrenCount=9},
    {name="校招", nodeId="人事-招聘", parentName="招聘", description="校招相关", topK=8, childrenCount=0},
    {name="社招", nodeId="人事-招聘", parentName="招聘", description="社招相关", topK=8, childrenCount=0},
    {name="面试", nodeId="人事-招聘", parentName="招聘", description="面试相关", topK=8, childrenCount=0},
    {name="简历", nodeId="人事-招聘", parentName="招聘", description="简历相关", topK=8, childrenCount=0},
    {name="offer", nodeId="人事-招聘", parentName="招聘", description="offer相关", topK=8, childrenCount=0},
    {name="背调", nodeId="人事-招聘", parentName="招聘", description="背调相关", topK=8, childrenCount=0},
    {name="入职", nodeId="人事-招聘", parentName="招聘", description="入职相关", topK=8, childrenCount=0},
    {name="试用期", nodeId="人事-招聘", parentName="招聘", description="试用期相关", topK=8, childrenCount=0},
    {name="转正", nodeId="人事-招聘", parentName="招聘", description="转正相关", topK=8, childrenCount=0},

    -- 技术支持
    {name="技术支持", nodeId="技术-支持", parentName=nil, description="技术支持总入口", topK=nil, childrenCount=9},
    {name="登录", nodeId="技术-支持", parentName="技术支持", description="登录相关", topK=8, childrenCount=0},
    {name="密码", nodeId="技术-支持", parentName="技术支持", description="密码相关", topK=8, childrenCount=0},
    {name="账号冻结", nodeId="技术-支持", parentName="技术支持", description="账号冻结相关", topK=8, childrenCount=0},
    {name="软件安装", nodeId="技术-支持", parentName="技术支持", description="软件安装相关", topK=8, childrenCount=0},
    {name="电脑故障", nodeId="技术-支持", parentName="技术支持", description="电脑故障相关", topK=8, childrenCount=0},
    {name="打印机", nodeId="技术-支持", parentName="技术支持", description="打印机相关", topK=8, childrenCount=0},
    {name="VPN", nodeId="技术-支持", parentName="技术支持", description="VPN相关", topK=8, childrenCount=0},
    {name="邮箱", nodeId="技术-支持", parentName="技术支持", description="邮箱相关", topK=8, childrenCount=0},
    {name="工单", nodeId="技术-支持", parentName="技术支持", description="工单相关", topK=8, childrenCount=0},

    -- 网络
    {name="网络", nodeId="技术-网络", parentName=nil, description="网络总入口", topK=nil, childrenCount=9},
    {name="断网", nodeId="技术-网络", parentName="网络", description="断网相关", topK=8, childrenCount=0},
    {name="速度慢", nodeId="技术-网络", parentName="网络", description="速度慢相关", topK=8, childrenCount=0},
    {name="WiFi", nodeId="技术-网络", parentName="网络", description="WiFi相关", topK=8, childrenCount=0},
    {name="内网", nodeId="技术-网络", parentName="网络", description="内网相关", topK=8, childrenCount=0},
    {name="外网", nodeId="技术-网络", parentName="网络", description="外网相关", topK=8, childrenCount=0},
    {name="DNS", nodeId="技术-网络", parentName="网络", description="DNS相关", topK=8, childrenCount=0},
    {name="代理", nodeId="技术-网络", parentName="网络", description="代理相关", topK=8, childrenCount=0},
    {name="防火墙", nodeId="技术-网络", parentName="网络", description="防火墙相关", topK=8, childrenCount=0},
    {name="路由器", nodeId="技术-网络", parentName="网络", description="路由器相关", topK=8, childrenCount=0},

    -- 账号
    {name="账号", nodeId="技术-账号", parentName=nil, description="账号总入口", topK=nil, childrenCount=9},
    {name="注册", nodeId="技术-账号", parentName="账号", description="注册相关", topK=8, childrenCount=0},
    {name="绑定手机", nodeId="技术-账号", parentName="账号", description="绑定手机相关", topK=8, childrenCount=0},
    {name="重置密码", nodeId="技术-账号", parentName="账号", description="重置密码相关", topK=8, childrenCount=0},
    {name="修改邮箱", nodeId="技术-账号", parentName="账号", description="修改邮箱相关", topK=8, childrenCount=0},
    {name="修改手机号", nodeId="技术-账号", parentName="账号", description="修改手机号相关", topK=8, childrenCount=0},
    {name="登录异常", nodeId="技术-账号", parentName="账号", description="登录异常相关", topK=8, childrenCount=0},
    {name="权限申请", nodeId="技术-账号", parentName="账号", description="权限申请相关", topK=8, childrenCount=0},
    {name="账号注销", nodeId="技术-账号", parentName="账号", description="账号注销相关", topK=8, childrenCount=0},
    {name="多因子认证", nodeId="技术-账号", parentName="账号", description="多因子认证相关", topK=8, childrenCount=0},

    -- 客服
    {name="客服", nodeId="服务-支持", parentName=nil, description="客服总入口", topK=nil, childrenCount=9},
    {name="退款", nodeId="服务-支持", parentName="客服", description="退款相关", topK=8, childrenCount=0},
    {name="退货", nodeId="服务-支持", parentName="客服", description="退货相关", topK=8, childrenCount=0},
    {name="物流", nodeId="服务-支持", parentName="客服", description="物流相关", topK=8, childrenCount=0},
    {name="发货", nodeId="服务-支持", parentName="客服", description="发货相关", topK=8, childrenCount=0},
    {name="签收异常", nodeId="服务-支持", parentName="客服", description="签收异常相关", topK=8, childrenCount=0},
    {name="修改地址", nodeId="服务-支持", parentName="客服", description="修改地址相关", topK=8, childrenCount=0},
    {name="取消订单", nodeId="服务-支持", parentName="客服", description="取消订单相关", topK=8, childrenCount=0},
    {name="投诉", nodeId="服务-支持", parentName="客服", description="投诉相关", topK=8, childrenCount=0},
    {name="售后", nodeId="服务-支持", parentName="客服", description="售后相关", topK=8, childrenCount=0},

    -- 教务
    {name="教务", nodeId="教育-教务", parentName=nil, description="教务总入口", topK=nil, childrenCount=9},
    {name="课程", nodeId="教育-教务", parentName="教务", description="课程相关", topK=8, childrenCount=0},
    {name="报名", nodeId="教育-教务", parentName="教务", description="报名相关", topK=8, childrenCount=0},
    {name="退课", nodeId="教育-教务", parentName="教务", description="退课相关", topK=8, childrenCount=0},
    {name="考试", nodeId="教育-教务", parentName="教务", description="考试相关", topK=8, childrenCount=0},
    {name="成绩", nodeId="教育-教务", parentName="教务", description="成绩相关", topK=8, childrenCount=0},
    {name="补考", nodeId="教育-教务", parentName="教务", description="补考相关", topK=8, childrenCount=0},
    {name="证书", nodeId="教育-教务", parentName="教务", description="证书相关", topK=8, childrenCount=0},
    {name="学费", nodeId="教育-教务", parentName="教务", description="学费相关", topK=8, childrenCount=0},
    {name="课表", nodeId="教育-教务", parentName="教务", description="课表相关", topK=8, childrenCount=0},
}

for _, node in ipairs(data) do
    put(node)
end

return #data