import request from '@/utils/request'

// 当前可实际扣回结算的处罚导入：CpsSettlementPenaltyController POST /cps/settlement/penalties
// Request: {requestKey, platform, fileName, sourceReference, statementPeriod,
//           lines:[{orderLineId, allianceStatusTime, cumulativeRecoveredCommission, rawEvidence}]}
export function importSettlementPenalties(data) {
  return request({
    url: '/cps/settlement/penalties',
    method: 'post',
    data: data
  })
}

// GET /cps/settlement/penalties/{id} -> Result(importId, sourceImportId, lines)
export function getSettlementPenaltyResult(id) {
  return request({
    url: '/cps/settlement/penalties/' + id,
    method: 'get'
  })
}

// POST /cps/settlement/penalties/{id}/retry
export function retrySettlementPenaltyImport(id) {
  return request({
    url: '/cps/settlement/penalties/' + id + '/retry',
    method: 'post'
  })
}

// Legacy capture-only import: CpsPunishmentImportController POST /cps/punishments/import.
// This writes raw statement rows; it does not apply settlement deductions or expose preview/results.
export function recordPunishmentStatement(data) {
  return request({
    url: '/cps/punishments/import',
    method: 'post',
    data: data
  })
}
