import request from '@/utils/request'

// The server is the sole source of shared CPS option values and labels.
export function getCpsDict() {
  return request({
    url: '/admin/dict',
    method: 'get'
  })
}
