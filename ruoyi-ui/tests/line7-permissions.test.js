const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const compiler = require('vue-template-compiler')

const root = path.resolve(__dirname, '../..')
const read = file => fs.readFileSync(path.join(root, file), 'utf8')

test('commission button permissions are granted by existing menu keys and accepted by the controllers', () => {
  const baseline = read('ruoyi-admin/src/main/resources/db/migration/V1__baseline.sql')
  for (const [page, controller] of [
    ['account', 'SysAgentCommissionAccountController'],
    ['flow', 'SysCommissionFlowController'],
    ['config', 'SysCommissionConfigController'],
    ['order', 'SysOrderCommissionController']
  ]) {
    const template = read(`ruoyi-ui/src/views/system/commission/${page}/index.vue`)
    const backend = read(`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/${controller}.java`)
    const keys = [...template.matchAll(/v-hasPermi="\['([^']+)'\]"/g)].map(match => match[1])
    assert.ok(keys.length > 0)
    for (const key of keys) {
      assert.ok(baseline.includes(`'${key}'`), `${key} missing from migration menus`)
      assert.ok(backend.includes(`hasPermi('${key}')`), `${key} missing from controller`)
    }
  }
})

test('settled orders produced by the backend show the refund action and refunded orders do not', () => {
  const source = read('ruoyi-ui/src/views/system/commission/order/index.vue')
  const template = compiler.parseComponent(source).template.content
  const compiled = compiler.compile(template)
  assert.deepEqual(compiled.errors, [])
  const button = template.match(/<el-button\s+v-if="([^"]+)"[^>]*?@click="handleRefund\(scope\.row\)"/)
  assert.ok(button, 'refund button must have a visibility condition')
  const visible = new Function('scope', `return ${button[1]}`)
  assert.equal(visible({ row: { orderStatus: 'confirmed' } }), true)
  assert.equal(visible({ row: { orderStatus: 'settled' } }), true)
  assert.equal(visible({ row: { orderStatus: 'pending' } }), false)
  assert.equal(visible({ row: { orderStatus: 'refunded' } }), false)
  assert.match(template, /<el-option label="已结算" value="confirmed"\s*\/>/)
})
