import request from '@/utils/request'

// Shared platform, status and flow dictionaries for CPS administration.
export function getCpsDict() {
  return request({
    url: '/admin/dict',
    method: 'get'
  })
}
