<template>
  <div class="app-container">
    <el-alert title="返佣规则采用版本化发布；编辑草稿不影响已下单订单。比例填写百分数，保存时转换为后端小数。发布由服务端校验安全边界。" type="info" :closable="false" />
    <el-row :gutter="10" class="mb8" style="margin-top:16px">
      <el-col :span="1.5"><el-button type="primary" plain size="mini" icon="el-icon-plus" @click="create" v-hasPermi="['system:commission:rule:add']">新建规则</el-button></el-col>
      <right-toolbar @queryTable="load" />
    </el-row>
    <el-table v-loading="loading" :data="rows">
      <el-table-column label="规则 ID" prop="id" width="100" />
      <el-table-column label="平台" prop="platform" width="100"><template slot-scope="s">{{ platformLabel(s.row.platform) }}</template></el-table-column>
      <el-table-column label="修订号" prop="revision" width="100" />
      <el-table-column label="状态" width="110"><template slot-scope="s"><el-tag :type="s.row.archived ? 'info' : 'success'">{{ s.row.archived ? '已停用' : '草稿' }}</el-tag></template></el-table-column>
      <el-table-column label="规则摘要" min-width="300" show-overflow-tooltip><template slot-scope="s">{{ summary(s.row.config) }}</template></el-table-column>
      <el-table-column label="操作" width="250" fixed="right"><template slot-scope="s">
        <el-button type="text" size="mini" @click="edit(s.row)" v-hasPermi="['system:commission:rule:edit']">编辑</el-button>
        <el-button type="text" size="mini" @click="publish(s.row)" v-if="!s.row.archived" v-hasPermi="['system:commission:rule:edit']">发布</el-button>
        <el-button type="text" size="mini" @click="history(s.row)" v-hasPermi="['system:commission:rule:query']">历史版本</el-button>
        <el-button type="text" size="mini" style="color:#f56c6c" @click="disable(s.row)" v-if="!s.row.archived" v-hasPermi="['system:commission:rule:remove']">停用</el-button>
      </template></el-table-column>
    </el-table>

    <el-dialog :title="form.id ? '编辑返佣规则' : '新建返佣规则'" :visible.sync="open" width="960px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" label-width="155px" v-loading="saving">
        <el-row :gutter="18">
          <el-col :span="12"><el-form-item label="平台"><el-select v-model="form.platform" :disabled="!!form.id" placeholder="请选择平台"><el-option v-for="p in platforms" :key="p.value" :label="p.label" :value="p.value.toUpperCase()" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="平台服务费率 (%)"><el-input-number v-model="form.serviceRatePct" :min="0" :max="100" :precision="4" :step="0.1" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="京东非自营扣减 (%)"><el-input-number v-model="form.jdNonSelfRatePct" :min="0" :max="100" :precision="4" :step="0.1" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="最大联盟佣金 (元)"><el-input v-model="form.maximumCommission" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="最小毛利 (元)"><el-input v-model="form.minimumMargin" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="打款成本率 (%)"><el-input-number v-model="form.payoutCostRatePct" :min="0" :max="100" :precision="4" :step="0.1" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="最低返利 (元)"><el-input v-model="form.minimumRebate" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="定向与阶梯同时启用"><el-switch v-model="form.targetedWithTier" /></el-form-item></el-col>
        </el-row>
        <el-divider content-position="left">返佣优先规则</el-divider>
        <el-table :data="form.proportions" border size="mini">
          <el-table-column label="规则键" min-width="140"><template slot-scope="s"><el-input v-model="s.row.key" /></template></el-table-column>
          <el-table-column label="类别" width="145"><template slot-scope="s"><el-select v-model="s.row.kind"><el-option v-for="v in kinds" :key="v" :value="v" :label="v" /></el-select></template></el-table-column>
          <el-table-column label="模式" width="180"><template slot-scope="s"><el-select v-model="s.row.mode"><el-option v-for="v in modes" :key="v" :value="v" :label="v" /></el-select></template></el-table-column>
          <el-table-column label="比例 (%)" width="145"><template slot-scope="s"><el-input-number v-model="s.row.ratePct" :min="0" :max="100" :precision="4" /></template></el-table-column>
          <el-table-column label="新人前 N 单" width="135"><template slot-scope="s"><el-input-number v-model="s.row.firstN" :min="0" :precision="0" /></template></el-table-column>
          <el-table-column label="沉默天数" width="130"><template slot-scope="s"><el-input-number v-model="s.row.dormantDays" :min="0" :precision="0" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" @click="removeProportion(s.$index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button size="mini" plain style="margin-top:10px" @click="addProportion">添加比例规则</el-button>
        <el-divider content-position="left">返佣档位</el-divider>
        <el-table :data="form.tiers" border size="mini">
          <el-table-column label="所属规则键" min-width="180"><template slot-scope="s"><el-input v-model="s.row.key" /></template></el-table-column>
          <el-table-column label="订单数门槛" width="180"><template slot-scope="s"><el-input-number v-model="s.row.count" :min="0" :precision="0" /></template></el-table-column>
          <el-table-column label="金额门槛 (元)" width="180"><template slot-scope="s"><el-input v-model="s.row.amount" /></template></el-table-column>
          <el-table-column label="比例 (%)" width="160"><template slot-scope="s"><el-input-number v-model="s.row.ratePct" :min="0" :max="100" :precision="4" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" @click="form.tiers.splice(s.$index,1)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button size="mini" plain style="margin-top:10px" @click="addTier">添加档位</el-button>
        <el-divider content-position="left">奖励激励</el-divider>
        <el-table :data="form.incentives" border size="mini">
          <el-table-column label="激励键" min-width="150"><template slot-scope="s"><el-input v-model="s.row.key" /></template></el-table-column>
          <el-table-column label="类型" min-width="190"><template slot-scope="s"><el-select v-model="s.row.kind"><el-option v-for="v in incentiveKinds" :key="v" :value="v" :label="v" /></el-select></template></el-table-column>
          <el-table-column label="固定金额 (元)" width="160"><template slot-scope="s"><el-input v-model="s.row.fixed" /></template></el-table-column>
          <el-table-column label="比例 (%)" width="150"><template slot-scope="s"><el-input-number v-model="s.row.ratePct" :min="0" :max="100" :precision="4" /></template></el-table-column>
          <el-table-column label="门槛 N" width="130"><template slot-scope="s"><el-input-number v-model="s.row.n" :min="0" :precision="0" /></template></el-table-column>
          <el-table-column label="有效期天数" width="140"><template slot-scope="s"><el-input-number v-model="s.row.withinDays" :min="0" :precision="0" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" @click="form.incentives.splice(s.$index,1)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button size="mini" plain style="margin-top:10px" @click="addIncentive">添加激励</el-button>
      </el-form>
      <div slot="footer"><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button><el-button @click="open=false">取消</el-button></div>
    </el-dialog>
    <el-drawer title="返佣规则历史版本" :visible.sync="historyOpen" size="650px" append-to-body>
      <el-table :data="versions" v-loading="historyLoading"><el-table-column label="版本 ID" prop="id" width="100"/><el-table-column label="修订" prop="revision" width="90"/><el-table-column label="生效时间" prop="effectiveAt" min-width="180"/><el-table-column label="状态" width="100"><template slot-scope="s"><el-tag :type="s.row.enabled?'success':'info'">{{s.row.enabled?'已发布':'已停用'}}</el-tag></template></el-table-column><el-table-column label="内容摘要" min-width="180"><template slot-scope="s">{{summary(s.row.config)}}</template></el-table-column></el-table>
    </el-drawer>
  </div>
</template>
<script>
import { listRules, createRule, updateRule, archiveRule, publishRule, ruleVersions, cpsDict } from '@/api/cps/settings'
const blank = () => ({ id: null, revision: null, platform: '', serviceRatePct: null, jdNonSelfRatePct: null, maximumCommission: '', minimumMargin: '', payoutCostRatePct: null, minimumRebate: '', targetedWithTier: false, proportions: [], tiers: [], incentives: [] })
export default {
  name: 'CpsRules',
  data() { return { loading: false, saving: false, rows: [], platforms: [], open: false, form: blank(), historyOpen: false, historyLoading: false, versions: [], kinds: ['TARGETED','NEWCOMER','DORMANCY','TIERED','BASE'], modes: ['FIXED','COMMISSION_TIERS','CUMULATIVE_TIERS','INVITATION_TIERS'], incentiveKinds: ['INVITATION_COMMISSION','INVITATION_FIRST','INVITATION_FIRST_N','ORDER_COUNT','FIRST_N_SUBSIDY','FORCED_FIRST'] } },
  created() { cpsDict().then(r => { this.platforms = (r.data && r.data.platforms) || [] }); this.load() },
  methods: {
    load() { this.loading = true; listRules().then(r => { this.rows = r.data || [] }).finally(() => { this.loading = false }) },
    platformLabel(v) { const p = this.platforms.find(x => x.value.toUpperCase() === v); return p ? p.label : v },
    summary(text) { try { const p = JSON.parse(text); return `${p.proportions.length} 条比例规则 · ${p.incentives.length} 项激励` } catch (_) { return '规则配置' } },
    create() { this.form = blank(); this.open = true },
    edit(row) { const p = JSON.parse(row.config); const f = blank(); Object.assign(f, { id: row.id, revision: row.revision, platform: row.platform, serviceRatePct: p.serviceRate == null ? null : Number(p.serviceRate) * 100, jdNonSelfRatePct: p.jdNonSelfRate == null ? null : Number(p.jdNonSelfRate) * 100, maximumCommission: p.maximumCommission, minimumMargin: p.minimumMargin, payoutCostRatePct: p.payoutCostRate == null ? null : Number(p.payoutCostRate) * 100, minimumRebate: p.minimumRebate, targetedWithTier: p.targetedWithTier, proportions: (p.proportions || []).map(x => ({ ...x, ratePct: x.rate == null ? null : Number(x.rate) * 100, tiers: undefined })), tiers: (p.proportions || []).flatMap(x => (x.tiers || []).map(t => ({ key: x.key, ...t, ratePct: t.rate == null ? null : Number(t.rate) * 100 }))), incentives: (p.incentives || []).map(x => ({ ...x, ratePct: x.rate == null ? null : Number(x.rate) * 100 })) }); this.form = f; this.open = true },
    payload() { const f = this.form; const tierMap = {}; f.tiers.forEach(t => { (tierMap[t.key] || (tierMap[t.key] = [])).push({ count: Number(t.count || 0), amount: t.amount === '' ? null : t.amount, rate: t.ratePct == null ? null : t.ratePct / 100 }) }); return { platform: f.platform, serviceRate: f.serviceRatePct == null ? null : f.serviceRatePct / 100, jdNonSelfRate: f.jdNonSelfRatePct == null ? null : f.jdNonSelfRatePct / 100, maximumCommission: f.maximumCommission, minimumMargin: f.minimumMargin, payoutCostRate: f.payoutCostRatePct == null ? null : f.payoutCostRatePct / 100, minimumRebate: f.minimumRebate, targetedWithTier: f.targetedWithTier, proportions: f.proportions.map(p => ({ key: p.key, kind: p.kind, mode: p.mode, rate: p.ratePct == null ? null : p.ratePct / 100, target: p.target || { users: [], items: [], shops: [], categories: [] }, firstN: Number(p.firstN || 0), dormantDays: Number(p.dormantDays || 0), publicActivity: p.publicActivity || null, tiers: tierMap[p.key] || [] })), incentives: f.incentives.map(x => ({ key: x.key, kind: x.kind, fixed: x.fixed === '' ? null : x.fixed, rate: x.ratePct == null ? null : x.ratePct / 100, n: Number(x.n || 0), withinDays: Number(x.withinDays || 0), startsAt: x.startsAt || null, endsAt: x.endsAt || null })) } },
    save() { if (!this.form.platform || !this.form.proportions.length || this.form.maximumCommission === '' || this.form.minimumMargin === '' || this.form.minimumRebate === '') { this.$modal.msgError('平台、金额边界和至少一条比例规则为必填'); return } this.saving = true; const data = this.payload(); const task = this.form.id ? updateRule(this.form.id, { revision: this.form.revision, policy: data }) : createRule(data); task.then(() => { this.$modal.msgSuccess('草稿保存成功'); this.open = false; this.load() }).finally(() => { this.saving = false }) },
    publish(row) { this.$modal.confirm('发布后仅影响之后付款的订单。继续发布此修订吗？').then(() => publishRule(row.id, row.revision)).then(() => { this.$modal.msgSuccess('规则发布成功'); this.load() }).catch(() => {}) },
    disable(row) { this.$modal.confirm('停用将追加停用版本并归档草稿，历史快照保留。确认停用？').then(() => archiveRule(row.id, row.revision)).then(() => { this.$modal.msgSuccess('规则已停用'); this.load() }).catch(() => {}) },
    history(row) { this.historyOpen = true; this.historyLoading = true; ruleVersions(row.id).then(r => { this.versions = r.data || [] }).finally(() => { this.historyLoading = false }) },
    addProportion() { this.form.proportions.push({ key: '', kind: 'BASE', mode: 'FIXED', ratePct: 0, firstN: 1, dormantDays: 1, target: { users: [], items: [], shops: [], categories: [] }, publicActivity: '' }) },
    removeProportion(i) { this.form.proportions.splice(i, 1) },
    addTier() { this.form.tiers.push({ key: '', count: 0, amount: '0.00', ratePct: 0 }) },
    addIncentive() { this.form.incentives.push({ key: '', kind: 'ORDER_COUNT', fixed: '0.00', ratePct: 0, n: 1, withinDays: 1, startsAt: null, endsAt: null }) }
  }
}
</script>
