import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/views/h5/HomeView.vue') },
    { path: '/search', name: 'search', component: () => import('@/views/h5/SearchView.vue') },
    { path: '/trip/:id', name: 'trip-detail', component: () => import('@/views/h5/TripDetailView.vue') },
    { path: '/order/:orderNo', name: 'order-pay', component: () => import('@/views/h5/OrderPayView.vue') },
    { path: '/pay/:orderNo', name: 'pay', component: () => import('@/views/h5/PayView.vue') },
    { path: '/coupons', name: 'coupons', component: () => import('@/views/h5/CouponListView.vue') },
    { path: '/mine', name: 'mine', component: () => import('@/views/h5/MineView.vue') },
    { path: '/assistant', name: 'assistant', component: () => import('@/views/h5/AssistantView.vue') },
    { path: '/publish', name: 'publish', component: () => import('@/views/h5/PublishTripView.vue') },
    { path: '/driver/trips', name: 'driver-trips', component: () => import('@/views/h5/DriverTripsView.vue') },
    { path: '/certification', name: 'certification', component: () => import('@/views/h5/CertificationView.vue') },
    { path: '/support', name: 'support', component: () => import('@/views/h5/SupportView.vue') },
  ],
})

export default router
