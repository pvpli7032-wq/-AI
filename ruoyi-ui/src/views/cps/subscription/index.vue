<template>
  <div class="app-container">
    <el-alert title="订阅消息群发尚未接入：当前代码没有模板管理、发送任务或小程序订阅授权的管理端接口。此页不展示虚构数据，也不提供假保存入口。" type="warning" :closable="false" />
    <el-card shadow="never" style="margin-top:16px"><div slot="header"><span>尚待后端接口</span></div><ul><li>订阅消息模板查询与启用状态</li><li>会员筛选后的收件人预览（使用会员数据来源）</li><li>发送内容校验、群发任务提交与结果查询</li><li>发送审计记录</li></ul></el-card>
  </div>
</template>
<script>
export default { name: 'CpsSubscription' }
</script>
