// 开发服务器和显式开关同时满足才注册页面，生产构建始终关闭。
export const seckillOpsEnabled = import.meta.env.DEV && import.meta.env.VITE_SECKILL_OPS_ENABLED === 'true';
