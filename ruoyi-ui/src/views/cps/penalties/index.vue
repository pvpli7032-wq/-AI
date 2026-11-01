<template>
  <div class="app-container">
    <el-alert type="warning" :closable="false" show-icon class="gap-alert" title="当前后端没有文件模板下载、文件上传、预览、导入记录列表或回显原文件名接口。请先在线下核对联盟模板和每行来源证据；可用接口要求显式提供订单行 ID、联盟状态时间及累计追回佣金证据。以下表单直接提交真实扣回接口，不解析或伪造预览结果。" />
    <el-card shadow="never" class="panel">
      <div slot="header"><span>提交处罚证据</span></div>
      <el-form ref="importForm" :model="form" :rules="rules" label-width="150px" v-loading="submitting">
        <el-form-item label="平台" prop="platform"><el-select v-model="form.platform" placeholder="请选择平台"><el-option v-for="item in dict.platforms" :key="item.value" :label="item.label" :value="String(item.value).toUpperCase()" /></el-select></el-form-item>
        <el-form-item label="来源文件名" prop="fileName"><el-input v-model="form.fileName" maxlength="500" placeholder="填写联盟下载的来源文件名" /></el-form-item>
        <el-form-item label="来源单据 / 批次" prop="sourceReference"><el-input v-model="form.sourceReference" maxlength="500" placeholder="填写联盟处罚单据编号或来源批次" /></el-form-item>
        <el-form-item label="单据月份"><el-date-picker v-model="form.periodDate" type="month" value-format="yyyy-MM" placeholder="选择月份" /></el-form-item>
        <el-form-item label="幂等请求键" prop="requestKey"><el-input v-model="form.requestKey" maxlength="128" placeholder="同一批次重试必须沿用相同请求键" /><div class="el-form-item__error static-help">相同请求键和内容会返回已有导入；相同请求键但内容不同会报错。</div></el-form-item>
        <el-form-item label="处罚明细 JSON" prop="linesText"><el-input v-model="form.linesText" type="textarea" :rows="12" placeholder='[{"orderLineId":123,"allianceStatusTime":"2026-10-31T10:00:00Z","cumulativeRecoveredCommission":"12.34","rawEvidence":"联盟原始处罚行"}]' /><div class="help">最多 500 行；每行必须有唯一 orderLineId、联盟状态时间、累计追回佣金（无数值时传 null）及 rawEvidence 原始证据。金额按字符串输入，不做前端浮点计算。</div></el-form-item>
        <el-form-item><el-button type="primary" :loading="submitting" :disabled="submitting" v-hasPermi="['system:commission:punish:import']" @click="confirmImport">确认导入并处理</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-card shadow="never" class="panel">
      <div slot="header"><span>导入批次结果</span></div>
      <el-form :inline="true" size="small" @submit.native.prevent="loadResult">
        <el-form-item label="导入批次 ID"><el-input v-model="lookupId" placeholder="输入导入接口返回的 importId" @keyup.enter.native="loadResult" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="resultLoading" @click="loadResult">查询结果</el-button></el-form-item>
        <el-form-item v-if="result"><span>来源记录 ID：{{ result.sourceImportId || '-' }}</span></el-form-item>
      </el-form>
      <el-table v-loading="resultLoading" :data="result ? result.lines : []" border>
        <el-table-column label="行号" prop="lineNo" width="90" />
        <el-table-column label="订单行 ID" prop="orderLineId" min-width="140" />
        <el-table-column label="处理状态" prop="status" width="150" />
        <el-table-column label="结果代码" prop="resultCode" min-width="300" show-overflow-tooltip />
        <template slot="empty"><span>{{ result ? '该批次暂无明细' : '请输入批次 ID 查询，当前接口没有批次列表' }}</span></template>
      </el-table>
      <div class="pager-gap">{{ result && result.lines ? '显示该批次全部行（结果接口不分页）' : '' }}</div>
    </el-card>
  </div>
</template>

<script>
import { getCpsDict } from '@/api/cps/dict'
import { importSettlementPenalties, getSettlementPenaltyResult } from '@/api/cps/penalties'

export default {
  name: 'CpsPenalties',
  data() {
    return {
      submitting: false,
      resultLoading: false,
      dict: { platforms: [] },
      lookupId: '',
      result: null,
      form: { platform: '', fileName: '', sourceReference: '', periodDate: '', requestKey: '', linesText: '' },
      rules: {
        platform: [{ required: true, message: '请选择平台', trigger: 'change' }],
        fileName: [{ required: true, message: '请填写来源文件名', trigger: 'blur' }],
        sourceReference: [{ required: true, message: '请填写来源单据 / 批次', trigger: 'blur' }],
        requestKey: [{ required: true, message: '请填写幂等请求键', trigger: 'blur' }],
        linesText: [{ required: true, message: '请填写处罚行证据 JSON', trigger: 'blur' }]
      }
    }
  },
  created() { getCpsDict().then(res => { this.dict = Object.assign(this.dict, res.data || {}) }).catch(() => {}) },
  methods: {
    parseLines() {
      let lines
      try { lines = JSON.parse(this.form.linesText) } catch (e) { this.$modal.msgError('处罚明细必须是有效 JSON 数组'); return null }
      if (!Array.isArray(lines) || lines.length < 1 || lines.length > 500) { this.$modal.msgError('处罚明细必须包含 1 至 500 行'); return null }
      for (let i = 0; i < lines.length; i++) {
        const row = lines[i]
        if (!row || !Number.isInteger(Number(row.orderLineId)) || Number(row.orderLineId) <= 0 || !row.allianceStatusTime || !Object.prototype.hasOwnProperty.call(row, 'cumulativeRecoveredCommission') || typeof row.rawEvidence !== 'string' || !row.rawEvidence.trim()) {
          this.$modal.msgError('第 ' + (i + 1) + ' 行字段无效，请核对 DTO 所需的 orderLineId、allianceStatusTime、cumulativeRecoveredCommission、rawEvidence')
          return null
        }
        if (row.cumulativeRecoveredCommission !== null && (typeof row.cumulativeRecoveredCommission !== 'string' || !/^\\d+(\\.\\d{1,8})?$/.test(row.cumulativeRecoveredCommission))) {
          this.$modal.msgError('第 ' + (i + 1) + ' 行累计追回佣金必须是非负金额字符串或 null')
          return null
        }
        row.orderLineId = Number(row.orderLineId)
      }
      return lines
    },
    confirmImport() {
      this.$refs.importForm.validate(valid => {
        if (!valid || this.submitting) return
        const lines = this.parseLines()
        if (!lines) return
        const body = {
          requestKey: this.form.requestKey,
          platform: this.form.platform,
          fileName: this.form.fileName,
          sourceReference: this.form.sourceReference,
          statementPeriod: this.form.periodDate || null,
          lines: lines
        }
        const total = lines.length
        this.submitting = true
        this.$modal.confirm('将处理 ' + total + ' 笔处罚证据。操作可能扣回对应返利，失败项可能要求人工复核；请确认来源和累计追回金额准确。', '确认处罚导入', { type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '取消' }).then(() => {
          return importSettlementPenalties(body)
        }).then(res => {
          const data = res.data || {}
          this.lookupId = String(data.importId || '')
          this.result = data
          this.$modal.msgSuccess('处罚导入已处理，请核对逐行结果')
          this.form = { platform: this.form.platform, fileName: '', sourceReference: '', periodDate: '', requestKey: '', linesText: '' }
        }).catch(() => {}).finally(() => { this.submitting = false })
      })
    },
    loadResult() {
      const id = Number(this.lookupId)
      if (!Number.isInteger(id) || id <= 0 || this.resultLoading) { if (!(Number.isInteger(id) && id > 0)) this.$modal.msgError('请输入有效的导入批次 ID'); return }
      this.resultLoading = true
      getSettlementPenaltyResult(id).then(res => { this.result = res.data || null }).catch(() => {}).finally(() => { this.resultLoading = false })
    }
  }
}
</script>

<style scoped>
.gap-alert { margin-bottom: 16px; }
.panel { margin-bottom: 16px; }
.help, .static-help { color: #909399; line-height: 1.5; }
.static-help { position: static; }
.pager-gap { min-height: 24px; margin-top: 10px; color: #909399; }
</style>
