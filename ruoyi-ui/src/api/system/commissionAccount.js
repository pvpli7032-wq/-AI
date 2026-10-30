import request from '@/utils/request'

// 查询佣金账户列表
export function listAccount(query) {
  return request({
    url: '/system/commissionAccount/list',
    method: 'get',
    params: query
  })
}

// 查询佣金账户详细
export function getAccount(accountId) {
  return request({
    url: '/system/commissionAccount/' + accountId,
    method: 'get'
  })
}

// 冻结账户
export function freezeAccount(accountId) {
  return request({
    url: '/system/commissionAccount/freeze/' + accountId,
    method: 'put'
  })
}

// 解冻账户
export function unfreezeAccount(accountId) {
  return request({
    url: '/system/commissionAccount/unfreeze/' + accountId,
    method: 'put'
  })
}

// 导出佣金账户
export function exportAccount(query) {
  return request({
    url: '/system/commissionAccount/export',
    method: 'get',
    params: query
  })
}
