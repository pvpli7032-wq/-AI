import request from '@/utils/request'

// 订单列表：CpsOrderQueryController GET /cps/orders
// 后端只接受 platform、status（内部状态）、method（归因方式）三个筛选，分页用 pageNum、pageSize。
// 订单号、子订单号、下单时间、用户 ID、是否可疑的筛选后端尚未提供，页面不传无效参数。
export function listCpsOrders(query) {
  return request({
    url: '/cps/orders',
    method: 'get',
    params: query
  })
}

// 订单详情：GET /cps/orders/{id}
// 返回 data.order（订单行）、data.attributionHistory（归因历史）、data.statusHistory（状态历史）
export function getCpsOrder(id) {
  return request({
    url: '/cps/orders/' + id,
    method: 'get'
  })
}
