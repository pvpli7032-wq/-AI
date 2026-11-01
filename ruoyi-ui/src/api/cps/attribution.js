import request from '@/utils/request'

// 无主订单列表：CpsAttributionController GET /system/commission/unclaimed
// platform 后端按 Platform 枚举解析，统一转大写；limit 取 1~500，接口不分页。
export function listUnclaimedOrders(query) {
  const params = Object.assign({}, query || {})
  if (params.platform) {
    params.platform = String(params.platform).toUpperCase()
  }
  return request({
    url: '/system/commission/unclaimed',
    method: 'get',
    params: params
  })
}

// 指定归属（人工归因）：POST /system/commission/unclaimed/{orderLineId}/assign
// 入参 { userId, reason }，reason 必填，后端写入审计。
export function assignUnclaimedOrder(orderLineId, data) {
  return request({
    url: '/system/commission/unclaimed/' + orderLineId + '/assign',
    method: 'post',
    data: data
  })
}

// 无主订单到期归平台（人工触发）：POST /system/commission/unclaimed/expire
export function expireUnclaimedOrders() {
  return request({
    url: '/system/commission/unclaimed/expire',
    method: 'post'
  })
}

// 归因后结算重试：POST /system/commission/unclaimed/retry-settlement
export function retryUnclaimedSettlement() {
  return request({
    url: '/system/commission/unclaimed/retry-settlement',
    method: 'post'
  })
}
