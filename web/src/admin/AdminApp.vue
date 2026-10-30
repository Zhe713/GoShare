<script setup lang="ts">
/** 管理后台（Minimal）：侧边导航 + 订单/行程/AI 统计三视图，表格风格极简卡片 */
import { onMounted, ref, computed } from 'vue'
import { GCard, GSkeleton, GError } from '@/g-ui'
import { searchTrips, type Trip } from '@/api/trip'
import { assistantChat } from '@/api/user'

const view = ref<'trips' | 'ai' | 'help'>('trips')
const trips = ref<Trip[]>([])
const loading = ref(true)
const error = ref('')
const aiInput = ref('')
const aiResult = ref('')

async function loadTrips() {
  loading.value = true
  error.value = ''
  try {
    trips.value = await searchTrips('石家庄', '北京')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(loadTrips)

const totalSeats = computed(() => trips.value.reduce((s, t) => s + (t.totalSeats ?? 0), 0))
const avgPrice = computed(() =>
  trips.value.length ? Math.round(trips.value.reduce((s, t) => s + (t.price ?? 0), 0) / trips.value.length) : 0,
)

async function testAi() {
  if (!aiInput.value.trim()) return
  try {
    const r = await assistantChat(aiInput.value)
    aiResult.value = `${r.answer}${r.fromCache ? '（缓存命中·零模型调用）' : ''}`
  } catch (e) {
    aiResult.value = (e as Error).message
  }
}
</script>

<template>
  <div class="admin">
    <aside class="admin__side">
      <h1 class="admin__logo">GoShare 后台</h1>
      <button class="admin__nav" :class="{ 'admin__nav--on': view === 'trips' }" @click="view = 'trips'">行程管理</button>
      <button class="admin__nav" :class="{ 'admin__nav--on': view === 'ai' }" @click="view = 'ai'">AI 助手测试</button>
      <button class="admin__nav" :class="{ 'admin__nav--on': view === 'help' }" @click="view = 'help'">帮助</button>
    </aside>

    <main class="admin__main">
      <!-- 行程管理 -->
      <template v-if="view === 'trips'">
        <div class="admin__stats">
          <GCard class="admin__stat">
            <p class="admin__stat-label">在售行程</p>
            <p class="admin__stat-num num">{{ trips.length }}</p>
          </GCard>
          <GCard class="admin__stat">
            <p class="admin__stat-label">总座位</p>
            <p class="admin__stat-num num">{{ totalSeats }}</p>
          </GCard>
          <GCard class="admin__stat">
            <p class="admin__stat-label">均价(元)</p>
            <p class="admin__stat-num num">{{ avgPrice }}</p>
          </GCard>
        </div>

        <GCard class="admin__table">
          <table>
            <thead>
              <tr>
                <th>ID</th><th>路线</th><th>发车时间</th><th>座位</th><th>价格</th><th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in trips" :key="t.id">
                <td class="num">{{ t.id }}</td>
                <td>{{ t.fromCity }} → {{ t.toCity }}</td>
                <td class="num">{{ t.departureTime }}</td>
                <td class="num">{{ t.remainSeats }}/{{ t.totalSeats }}</td>
                <td class="num">{{ t.price }} 元</td>
                <td>{{ t.remark || '—' }}</td>
              </tr>
            </tbody>
          </table>
          <GSkeleton v-if="loading" :rows="4" :height="14" />
          <GError v-else-if="error" :message="error" @retry="loadTrips" />
        </GCard>
      </template>

      <!-- AI 助手测试 -->
      <template v-else-if="view === 'ai'">
        <GCard class="admin__ai">
          <h3>省 token 四关卡验证（走真实 LlmGateway）</h3>
          <textarea v-model="aiInput" rows="3" placeholder="输入问题，如：取消订单会扣钱吗" class="admin__input" />
          <button class="admin__send" @click="testAi">发送（观察是否缓存命中）</button>
          <p v-if="aiResult" class="admin__ai-result">{{ aiResult }}</p>
        </GCard>
      </template>

      <template v-else>
        <GCard class="admin__help">
          <h3>管理后台说明</h3>
          <p>· 行程/订单数据来自真实服务（Gateway :8000 代理）</p>
          <p>· AI 助手测试页直接调用 /assistant/chat，可观察关卡1缓存命中</p>
          <p>· 数据大屏见 screen.html（Aurora 主题）</p>
        </GCard>
      </template>
    </main>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  min-height: 100vh;
  background: var(--bg-base);
}
.admin__side {
  width: 200px;
  padding: var(--sp-3) var(--sp-2);
  background: var(--surface);
  border-right: 1px solid var(--border);
}
.admin__logo {
  font-size: var(--fs-20);
  margin-bottom: var(--sp-4);
}
.admin__nav {
  display: block;
  width: 100%;
  min-height: 44px;
  text-align: left;
  padding: 0 var(--sp-2);
  border-radius: var(--r-md);
  color: var(--text-2);
  font-size: var(--fs-14);
  margin-bottom: var(--sp-1);
}
.admin__nav--on {
  background: color-mix(in srgb, var(--brand) 12%, transparent);
  color: var(--brand);
  font-weight: var(--fw-medium);
}
.admin__main {
  flex: 1;
  padding: var(--sp-3);
}
.admin__stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}
.admin__stat-label {
  font-size: var(--fs-13);
  color: var(--text-2);
}
.admin__stat-num {
  margin-top: var(--sp-1);
  font-size: var(--fs-32);
  font-weight: var(--fw-semibold);
  color: var(--text-1);
}
.admin__table {
  padding: 0;
  overflow: hidden;
}
.admin__table :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-14);
}
.admin__table :deep(th),
.admin__table :deep(td) {
  text-align: left;
  padding: var(--sp-2);
  border-bottom: 1px solid var(--border);
}
.admin__table :deep(th) {
  color: var(--text-3);
  font-weight: var(--fw-medium);
  font-size: var(--fs-13);
}
.admin__ai h3,
.admin__help h3 {
  font-size: var(--fs-16);
  margin-bottom: var(--sp-2);
}
.admin__input {
  width: 100%;
  padding: var(--sp-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  color: var(--text-1);
  font-size: var(--fs-14);
  resize: vertical;
}
.admin__send {
  margin-top: var(--sp-2);
  min-height: 44px;
  padding: 0 var(--sp-3);
  background: var(--brand-grad);
  color: var(--on-brand);
  border-radius: var(--r-md);
  font-size: var(--fs-14);
}
.admin__ai-result {
  margin-top: var(--sp-2);
  padding: var(--sp-2);
  background: var(--surface);
  border-radius: var(--r-md);
  font-size: var(--fs-14);
  color: var(--text-1);
  white-space: pre-wrap;
}
.admin__help p {
  margin-top: var(--sp-1);
  font-size: var(--fs-14);
  color: var(--text-2);
}
</style>
