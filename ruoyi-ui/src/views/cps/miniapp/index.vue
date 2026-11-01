<template>
  <div class="app-container">
    <el-alert title="小程序基础配置当前通过系统配置表管理。此页只读取已存在的 miniapp / wx.miniapp 配置，不创建未定义的键；对接密钥不会回显或向服务器读取。" type="info" :closable="false" />
    <el-table v-loading="loading" :data="rows"><el-table-column label="配置项" prop="configName" min-width="180"/><el-table-column label="配置键" prop="configKey" min-width="260"/><el-table-column label="配置值" min-width="240" show-overflow-tooltip><template slot-scope="s">{{s.row.configValue}}</template></el-table-column><el-table-column label="操作" width="90"><template slot-scope="s"><el-button type="text" size="mini" @click="edit(s.row)" v-hasPermi="['system:config:edit']">编辑</el-button></template></el-table-column></el-table>
    <el-empty v-if="!loading&&!rows.length" description="尚无小程序基础配置写入 sys_config；没有可编辑配置项。" />
    <el-dialog title="编辑小程序配置" :visible.sync="open" width="640px" append-to-body><el-form :model="form" label-width="150px"><el-form-item label="名称"><el-input v-model="form.configName" disabled/></el-form-item><el-form-item label="配置键"><el-input v-model="form.configKey" disabled/></el-form-item><el-form-item label="配置值"><el-input v-model="form.configValue" type="textarea" :rows="3"/></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" type="textarea"/></el-form-item></el-form><div slot="footer"><el-button type="primary" :loading="saving" @click="save">保存</el-button><el-button @click="open=false">取消</el-button></div></el-dialog>
  </div>
</template>
<script>
import { getCpsConfigList,getCpsConfig,saveCpsConfig } from '@/api/cps/settings'
export default {name:'CpsMiniappSettings',data(){return{loading:false,saving:false,rows:[],open:false,form:{}}},created(){this.load()},methods:{load(){this.loading=true;getCpsConfigList({pageNum:1,pageSize:300}).then(r=>{this.rows=(r.rows||[]).filter(x=>/^(miniapp\.|wx\.miniapp\.)/i.test(x.configKey)&&!/secret|token|key|password/i.test(x.configKey))}).finally(()=>{this.loading=false})},edit(row){getCpsConfig(row.configId).then(r=>{this.form={...r.data};this.open=true})},save(){this.saving=true;saveCpsConfig(this.form).then(()=>{this.$modal.msgSuccess('小程序配置保存成功');this.open=false;this.load()}).finally(()=>{this.saving=false})}}}
</script>
