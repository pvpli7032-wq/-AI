<template>
  <div class="app-container">
    <el-alert title="返佣规则按平台版本化保存与发布；发布只影响之后付款的订单。比例填写百分数，保存时转换为后端小数。发布的安全边界由服务端校验。" type="info" :closable="false" />
    <el-alert title="定向规则（按会员、商品、店铺、类目）缺少目标对象的后台维护接口，暂不支持新增；其余类别可直接保存。" type="warning" :closable="false" style="margin-top:10px" />
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
      <el-table-column label="操作" width="190" fixed="right"><template slot-scope="s">
        <el-button type="text" size="mini" @click="edit(s.row)" v-hasPermi="['system:commission:rule:edit']">编辑</el-button>
        <el-button type="text" size="mini" @click="publish(s.row)" v-if="!s.row.archived" v-hasPermi="['system:commission:rule:edit']">发布</el-button>
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
          <el-col :span="12"><el-form-item label="打款成本率 (%)"><el-input-number v-model="form.payoutCostRatePct" :min="0" :max="100" :precision="4" :step="0.1" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="最低返利 (元)"><el-input v-model="form.minimumRebate" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="定向与阶梯同时启用"><el-switch v-model="form.targetedWithTier" /></el-form-item></el-col>
        </el-row>
        <el-divider content-position="left">返佣比例规则</el-divider>
        <el-table :data="form.proportions" border size="mini">
          <el-table-column label="规则键" min-width="140"><template slot-scope="s"><el-input v-model="s.row.key" /></template></el-table-column>
          <el-table-column label="类别" width="130"><template slot-scope="s"><el-select v-model="s.row.kind" @change="onKindChange(s.row)"><el-option v-for="v in kinds" :key="v.value" :value="v.value" :label="v.label" /></el-select></template></el-table-column>
          <el-table-column label="模式" width="150"><template slot-scope="s"><el-select v-model="s.row.mode"><el-option v-for="v in modes" :key="v.value" :value="v.value" :label="v.label" /></el-select></template></el-table-column>
          <el-table-column label="比例 (%)" width="140"><template slot-scope="s"><el-input-number v-model="s.row.ratePct" :min="0" :max="100" :precision="4" /></template></el-table-column>
          <el-table-column label="新人前 N 单" width="130"><template slot-scope="s"><el-input-number v-model="s.row.firstN" :min="1" :precision="0" /></template></el-table-column>
          <el-table-column label="沉默天数" width="125"><template slot-scope="s"><el-input-number v-model="s.row.dormantDays" :min="1" :precision="0" /></template></el-table-column>
          <el-table-column label="公开活动名" width="150"><template slot-scope="s"><el-input v-model="s.row.publicActivity" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" @click="removeProportion(s.$index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button size="mini" plain style="margin-top:10px" @click="addProportion">添加比例规则</el-button>
        <el-divider content-position="left">比例档位（按规则键归属）</el-divider>
        <el-table :data="form.tiers" border size="mini">
          <el-table-column label="所属规则键" min-width="180"><template slot-scope="s"><el-select v-model="s.row.key" filterable allow-create default-first-option><el-option v-for="p in form.proportions" :key="p.key" :value="p.key" :label="p.key" /></el-select></template></el-table-column>
          <el-table-column label="订单数门槛" width="180"><template slot-scope="s"><el-input-number v-model="s.row.count" :min="0" :precision="0" /></template></el-table-column>
          <el-table-column label="金额门槛 (元)" width="180"><template slot-scope="s"><el-input v-model="s.row.amount" /></template></el-table-column>
          <el-table-column label="比例 (%)" width="160"><template slot-scope="s"><el-input-number v-model="s.row.ratePct" :min="0" :max="100" :precision="4" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" @click="form.tiers.splice(s.$index,1)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button size="mini" plain style="margin-top:10px" @click="addTier">添加档位</el-button>
        <el-divider content-position="left">奖励激励</el-divider>
        <el-table :data="form.incentives" border size="mini">
          <el-table-column label="激励键" min-width="140"><template slot-scope="s"><el-input v-model="s.row.key" /></template></el-table-column>
          <el-table-column label="类型" min-width="160"><template slot-scope="s"><el-select v-model="s.row.kind"><el-option v-for="v in incentiveKinds" :key="v.value" :value="v.value" :label="v.label" /></el-select></template></el-table-column>
          <el-table-column label="固定金额 (元)" width="150"><template slot-scope="s"><el-input v-model="s.row.fixed" /></template></el-table-column>
          <el-table-column label="比例 (%)" width="140"><template slot-scope="s"><el-input-number v-model="s.row.ratePct" :min="0" :max="100" :precision="4" /></template></el-table-column>
          <el-table-column label="门槛 N" width="125"><template slot-scope="s"><el-input-number v-model="s.row.n" :min="1" :precision="0" /></template></el-table-column>
          <el-table-column label="注册天数内" width="135"><template slot-scope="s"><el-input-number v-model="s.row.withinDays" :min="1" :precision="0" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" @click="form.incentives.splice(s.$index,1)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button size="mini" plain style="margin-top:10px" @click="addIncentive">添加激励</el-button>
      </el-form>
      <div slot="footer"><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button><el-button @click="open=false">取消</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import { listRules, createRule, updateRule, archiveRule, publishRule, cpsDict } from '@/api/cps/settings'
const emptyTarget = () => ({ users: [], items: [], shops: [], categories: [] })
const blank = () => ({ id: null, revision: null, platform: '', serviceRatePct: null, jdNonSelfRatePct: null, maximumCommission: '', payoutCostRatePct: null, minimumRebate: '', targetedWithTier: false, proportions: [], tiers: [], incentives: [] })
export default {
  name: 'CpsRules',
  data() {
    return {
      loading: false, saving: false, rows: [], platforms: [], open: false, form: blank(),
      kinds: [{ value: 'BASE', label: '平台基础' }, { value: 'NEWCOMER', label: '新人' }, { value: 'DORMANCY', label: '沉默回归' }, { value: 'TIERED', label: '阶梯' }, { value: 'TARGETED', label: '定向（暂不支持）' }],
      modes: [{ value: 'FIXED', label: '固定比例' }, { value: 'COMMISSION_TIERS', label: '佣金分档' }, { value: 'CUMULATIVE_TIERS', label: '累计分档' }, { value: 'INVITATION_TIERS', label: '邀请人数分档' }],
      incentiveKinds: [{ value: 'INVITATION_COMMISSION', label: '邀请提成比例' }, { value: 'INVITATION_FIRST', label: '邀请首单奖励' }, { value: 'INVITATION_FIRST_N', label: '邀请前 N 人奖励' }, { value: 'ORDER_COUNT', label: '下单数量奖励' }, { value: 'FIRST_N_SUBSIDY', label: '前 N 单补贴' }, { value: 'FORCED_FIRST', label: '新人首单保底' }]
    }
  },
  created() { cpsDict().then(r => { this.platforms = (r.data && r.data.platforms) || [] }).catch(() => { this.platforms = [] }); this.load() },
  methods: {
    load() { this.loading = true; listRules().then(r => { this.rows = r.data || [] }).catch(() => { this.rows = [] }).finally(() => { this.loading = false }) },
    platformLabel(v) { const p = this.platforms.find(x => String(x.value).toUpperCase() === v); return p ? p.label : v },
    summary(text) { try { const p = JSON.parse(text); return `${p.proportions.length} 条比例规则 · ${p.incentives.length} 项激励` } catch (_) { return '规则配置' } },
    create() { this.form = blank(); this.open = true },
    // Old drafts may still carry minimumMargin; it is neither edited nor published any more.
    edit(row) {
      const p = JSON.parse(row.config); const f = blank();
      Object.assign(f, {
        id: row.id, revision: row.revision, platform: row.platform,
        serviceRatePct: p.serviceRate == null ? null : Number(p.serviceRate) * 100,
        jdNonSelfRatePct: p.jdNonSelfRate == null ? null : Number(p.jdNonSelfRate) * 100,
        maximumCommission: p.maximumCommission, payoutCostRatePct: p.payoutCostRate == null ? null : Number(p.payoutCostRate) * 100,
        minimumRebate: p.minimumRebate, targetedWithTier: p.targetedWithTier,
        proportions: (p.proportions || []).map(x => ({ key: x.key, kind: x.kind, mode: x.mode, ratePct: x.rate == null ? null : Number(x.rate) * 100, firstN: Number(x.firstN || 1), dormantDays: Number(x.dormantDays || 1), publicActivity: x.publicActivity || '', target: emptyTarget() })),
        tiers: (p.proportions || []).flatMap(x => (x.tiers || []).map(t => ({ key: x.key, count: Number(t.count || 0), amount: t.amount == null ? '0.00' : String(t.amount), ratePct: t.rate == null ? null : Number(t.rate) * 100 }))),
        incentives: (p.incentives || []).map(x => ({ key: x.key, kind: x.kind, fixed: x.fixed == null ? '0.00' : String(x.fixed), ratePct: x.rate == null ? null : Number(x.rate) * 100, n: Number(x.n || 1), withinDays: Number(x.withinDays || 1), startsAt: x.startsAt || null, endsAt: x.endsAt || null }))
      });
      this.form = f; this.open = true
    },
    payload() {
      const f = this.form; const tierMap = {};
      f.tiers.forEach(t => { (tierMap[t.key] || (tierMap[t.key] = [])).push({ count: Number(t.count || 0), amount: t.amount === '' ? null : t.amount, rate: t.ratePct == null ? null : t.ratePct / 100 }) });
      return {
        platform: f.platform,
        serviceRate: f.serviceRatePct == null ? null : f.serviceRatePct / 100,
        jdNonSelfRate: f.jdNonSelfRatePct == null ? null : f.jdNonSelfRatePct / 100,
        maximumCommission: f.maximumCommission, payoutCostRate: f.payoutCostRatePct == null ? null : f.payoutCostRatePct / 100,
        minimumRebate: f.minimumRebate, targetedWithTier: f.targetedWithTier,
        proportions: f.proportions.map(p => ({ key: p.key, kind: p.kind, mode: p.mode, rate: p.ratePct == null ? null : p.ratePct / 100, target: emptyTarget(), firstN: Number(p.firstN || 1), dormantDays: Number(p.dormantDays || 1), publicActivity: p.publicActivity || null, tiers: tierMap[p.key] || [] })),
        incentives: f.incentives.map(x => ({ key: x.key, kind: x.kind, fixed: x.fixed === '' ? null : x.fixed, rate: x.ratePct == null ? null : x.ratePct / 100, n: Number(x.n || 1), withinDays: Number(x.withinDays || 1), startsAt: x.startsAt || null, endsAt: x.endsAt || null }))
      }
    },
    validatePolicy() {
      const f = this.form
      if (!f.platform) return '请选择平台'
      if (f.serviceRatePct == null || f.payoutCostRatePct == null) return '平台服务费率和打款成本率必填'
      if (f.maximumCommission === '' || Number(f.maximumCommission) <= 0) return '最大联盟佣金必须是大于 0 的分值金额'
      if (f.minimumRebate === '') return '最低返利必填'
      if (!f.proportions.length) return '至少配置一条比例规则'
      if (f.proportions.filter(p => p.kind === 'BASE').length !== 1) return '必须有且只有一条平台基础规则'
      for (const p of f.proportions) {
        if (!p.key) return '规则键必填'
        if (p.ratePct == null) return `规则 ${p.key} 的比例必填`
        if (p.kind === 'TARGETED') return '定向规则暂不支持：缺少目标对象的维护接口'
        if ((p.kind === 'NEWCOMER' || p.kind === 'DORMANCY') && Number(p.firstN) < 1) return `${p.key}：新人/沉默规则的前 N 单必须大于 0`
        if (p.kind === 'DORMANCY' && (Number(p.dormantDays) < 1 || !p.publicActivity)) return `${p.key}：沉默规则需要沉默天数和公开活动名称`
        if ((p.kind === 'TIERED') !== (p.mode === 'CUMULATIVE_TIERS')) return `${p.key}：累计分档只用于阶梯规则，阶梯规则只能用累计分档`
        const tiers = f.tiers.filter(t => t.key === p.key)
        if (p.mode !== 'FIXED' && !tiers.length) return `${p.key}：非固定模式必须配置档位`
        if (p.mode === 'FIXED' && tiers.length) return `${p.key}：固定模式不能配置档位`
      }
      for (const x of f.incentives) {
        if (!x.key) return '激励键必填'
        if (['INVITATION_FIRST_N', 'ORDER_COUNT', 'FIRST_N_SUBSIDY'].includes(x.kind) && Number(x.n) < 1) return `${x.key}：门槛 N 必须大于 0`
        if (x.kind === 'FORCED_FIRST' && Number(x.withinDays) < 1) return `${x.key}：新人首单保底需要注册天数窗口`
      }
      return null
    },
    save() {
      const error = this.validatePolicy()
      if (error) { this.$modal.msgError(error); return }
      this.saving = true
      const data = this.payload()
      const task = this.form.id ? updateRule(this.form.id, { revision: this.form.revision, policy: data }) : createRule(data)
      task.then(() => { this.$modal.msgSuccess('草稿保存成功'); this.open = false; this.load() }).finally(() => { this.saving = false })
    },
    publish(row) { this.$modal.confirm('发布后仅影响之后付款的订单。继续发布此修订吗？').then(() => publishRule(row.id, row.revision)).then(() => { this.$modal.msgSuccess('规则发布成功'); this.load() }).catch(() => {}) },
    disable(row) { this.$modal.confirm('停用将追加停用版本并归档草稿。确认停用？').then(() => archiveRule(row.id, row.revision)).then(() => { this.$modal.msgSuccess('规则已停用'); this.load() }).catch(() => {}) },
    onKindChange(row) {
      if (row.kind === 'TIERED') row.mode = 'CUMULATIVE_TIERS'
      else if (row.mode === 'CUMULATIVE_TIERS') row.mode = 'FIXED'
    },
    addProportion() { this.form.proportions.push({ key: '', kind: 'BASE', mode: 'FIXED', ratePct: 0, firstN: 1, dormantDays: 1, publicActivity: '', target: emptyTarget() }) },
    removeProportion(i) { this.form.proportions.splice(i, 1) },
    addTier() { this.form.tiers.push({ key: '', count: 0, amount: '0.00', ratePct: 0 }) },
    addIncentive() { this.form.incentives.push({ key: '', kind: 'ORDER_COUNT', fixed: '0.00', ratePct: 0, n: 1, withinDays: 1, startsAt: null, endsAt: null }) }
  }
}
</script>
