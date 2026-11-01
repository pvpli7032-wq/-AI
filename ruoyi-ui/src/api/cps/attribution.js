import request from '@/utils/request'
export function pidOccupancy(platform, itemId) {
  return request({ url: '/system/commission/pid/occupancy', method: 'get', params: { platform, itemId } })
}
