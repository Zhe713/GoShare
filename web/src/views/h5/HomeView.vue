<script setup lang="ts">
/** 首页：搜索入口 + 推荐行程（可换线路刷新）+ AI 助手入口 + 底部 TabBar（Glass） */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GTabBar, GTripCard, GSkeleton, GError, GSheet, GInput, GButton, GEmpty, GCard } from '@/g-ui'
import { searchTrips, type Trip } from '@/api/trip'

const router = useRouter()
const tab = ref('home')
const trips = ref<Trip[]>([])
const loading = ref(true)
const error = ref('')
/** 当前推荐线路（可换线路自更新） */
const routeFrom = ref('石家庄')
const routeTo = ref('北京')
const showPicker = ref(false)
const pickFrom = ref('石家庄')
const pickTo = ref('北京')

/** 热门线路（均为系统内已有未来班次的真实线路，点击直达有行程可看） */
const hotRoutes = [
  { from: '石家庄', to: '北京' },
  { from: '邯郸', to: '石家庄' },
  { from: '石家庄', to: '邯郸' },
  { from: '邯郸', to: '北京' },
  { from: '北京', to: '石家庄' },
  { from: '保定', to: '北京' },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    trips.value = await searchTrips(routeFrom.value, routeTo.value)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 换线路：确认后重新拉取该线路行程 */
function switchRoute() {
  const from = pickFrom.value.trim()
  const to = pickTo.value.trim()
  if (!from || !to) return
  routeFrom.value = from
  routeTo.value = to
  showPicker.value = false
  load()
}
</script>

<template>
  <div class="home">
    <GNavBar title="GoShare 顺风拼车" :show-back="false" />

    <!-- 搜索入口 -->
    <div class="home__search" @click="router.push('/search')">
      <svg class="home__search-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
        <path d="M10.5 4.5 a6 6 0 1 0 0.1 12 A6 6 0 0 0 10.5 4.5 M15.5 15.5 L20.5 20.5" />
      </svg>
      <span class="home__search-text">说句话搜行程，如「周五18点去邯郸」</span>
    </div>

    <!-- AI 助手入口 -->
    <div class="home__ai" @click="router.push('/assistant')">
      <div>
        <p class="home__ai-title">AI 出行助手</p>
        <p class="home__ai-sub">语义搜行程 · 取消前先算钱 · 工单秒回</p>
      </div>
      <span class="home__ai-arrow" aria-hidden="true">›</span>
    </div>

    <div class="home__section-row">
      <h3 class="home__section">推荐行程 · {{ routeFrom }} → {{ routeTo }}</h3>
      <button class="home__switch" type="button" @click="showPicker = true">换线路</button>
    </div>

    <div class="home__list">
      <template v-if="loading">
        <GCard v-for="i in 3" :key="i"><GSkeleton :rows="3" :height="14" /></GCard>
      </template>
      <GError v-else-if="error" :message="error" @retry="load" />
      <GEmpty v-else-if="!trips.length" text="该线路暂无行程，换条线路试试" />
      <GTripCard
        v-for="t in trips"
        :key="t.id"
        :trip="t"
        @click="router.push(`/trip/${t.id}`)"
      />
    </div>

    <GSheet v-model="showPicker" title="换一条线路">
      <GInput v-model="pickFrom" label="出发城市" placeholder="如：石家庄" />
      <GInput v-model="pickTo" label="到达城市" placeholder="如：北京" />
      <p class="home__pick-hint">热门线路（有现成班次）</p>
      <div class="home__chips">
        <button
          v-for="r in hotRoutes"
          :key="`${r.from}-${r.to}`"
          type="button"
          class="home__chip"
          @click="pickFrom = r.from; pickTo = r.to"
        >{{ r.from }} → {{ r.to }}</button>
      </div>
      <GButton type="primary" block class="home__pick-btn" @click="switchRoute">查看该线路行程</GButton>
    </GSheet>

    <GTabBar v-model="tab" :tabs="[
      { key: 'home', label: '首页', icon: 'home' },
      { key: 'search', label: '搜索', icon: 'search' },
      { key: 'coupons', label: '券包', icon: 'coupons' },
      { key: 'mine', label: '我的', icon: 'mine' },
    ]" />
  </div>
</template>

<style scoped>
.home {
  position: relative;
  min-height: 100vh;
  padding-bottom: var(--tabbar-h);
}

.home__search {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  margin: var(--sp-2);
  height: 48px;
  padding: 0 var(--sp-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1), var(--highlight);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  cursor: pointer;
}

.home__search-text {
  color: var(--text-2);
  font-size: var(--fs-14);
}

.home__ai {
  display: flex;
  align-items: center;
  margin: 0 var(--sp-2) var(--sp-3);
  padding: var(--sp-2);
  background: var(--brand-grad);
  border-radius: var(--r-lg);
  color: var(--on-brand);
  box-shadow: var(--shadow-2);
  cursor: pointer;
}

.home__ai-title {
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
}

.home__ai-sub {
  margin-top: 2px;
  font-size: var(--fs-12);
  opacity: 0.85;
}

.home__ai-arrow {
  margin-left: auto;
  font-size: var(--fs-24);
}

.home__section-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 var(--sp-2) var(--sp-2);
}

.home__section {
  font-size: var(--fs-16);
}

.home__switch {
  height: 32px;
  padding: 0 var(--sp-2);
  font-size: var(--fs-12);
  color: var(--brand);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: var(--highlight);
  cursor: pointer;
}

.home__pick-btn {
  margin-top: var(--sp-2);
}

.home__pick-hint {
  margin-top: var(--sp-2);
  font-size: var(--fs-12);
  color: var(--text-2);
}

.home__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1);
  margin-top: var(--sp-1);
}

.home__chip {
  padding: var(--sp-1) var(--sp-2);
  font-size: var(--fs-12);
  color: var(--brand);
  background: color-mix(in srgb, var(--brand) 10%, var(--surface));
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: var(--highlight);
  cursor: pointer;
}

.home__list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: 0 var(--sp-2);
}
</style>
