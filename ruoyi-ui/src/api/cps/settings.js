import request from '@/utils/request'

export function listRules() { return request({ url: '/cps/rules', method: 'get' }) }
export function createRule(data) { return request({ url: '/cps/rules', method: 'post', data }) }
export function updateRule(id, data) { return request({ url: '/cps/rules/' + id, method: 'put', data }) }
export function archiveRule(id, revision) { return request({ url: '/cps/rules/' + id, method: 'delete', params: { revision } }) }
export function publishRule(id, revision) { return request({ url: '/cps/rules/' + id + '/publish', method: 'post', params: { revision } }) }
export function ruleVersions(id) { return request({ url: '/cps/rules/' + id + '/versions', method: 'get' }) }
export function settlementHistory() { return request({ url: '/cps/rules/settlement-configuration', method: 'get' }) }
export function publishSettlement(data) { return request({ url: '/cps/rules/settlement-configuration', method: 'post', data }) }
export function getCpsConfigList(params) { return request({ url: '/system/config/list', method: 'get', params }) }
export function getCpsConfig(id) { return request({ url: '/system/config/' + id, method: 'get' }) }
export function saveCpsConfig(data) { return request({ url: '/system/config', method: 'put', data }) }
export function createCpsConfig(data) { return request({ url: '/system/config', method: 'post', data }) }
export function unionRows(params) { return request({ url: '/system/unionPlatform/list', method: 'get', params }) }
export function getUnion(id) { return request({ url: '/system/unionPlatform/' + id, method: 'get' }) }
export function addUnion(data) { return request({ url: '/system/unionPlatform', method: 'post', data }) }
export function editUnion(data) { return request({ url: '/system/unionPlatform', method: 'put', data }) }
export function setUnionStatus(data) { return request({ url: '/system/unionPlatform/changeStatus', method: 'put', data }) }
export function pidRows(platform) { return request({ url: '/system/commission/pid', method: 'get', params: { platform: platform.toUpperCase() } }) }
export function savePid(data) { return request({ url: '/system/commission/pid', method: 'put', data }) }
export function memberRows(params) { return request({ url: '/cps/members', method: 'get', params }) }
export function memberDetail(userId) { return request({ url: '/cps/members/' + userId, method: 'get' }) }
// Shared dictionary lives in api/cps/dict.js (navigation/dictionary branch).
// Defined locally so this branch builds on its own; switch to the shared import once merged.
export function cpsDict() { return request({ url: '/admin/dict', method: 'get' }) }
