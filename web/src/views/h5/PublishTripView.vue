<script setup lang="ts">
/** 发布行程（车主侧）：支持站点序列（途经点）+ 车辆快照 + 每公里单价（区间计价） */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GInput, GToast } from '@/g-ui'
import { publishTrip, type TripStop } from '@/api/trip'

const router = useRouter()
const fromCity = ref('')
const toCity = ref('')
const departureTime = ref('')
const totalSeats = ref(4)
const pricePerKm = ref('0.35')
const viaCity = ref('') // 途经城市（可选，逗号分隔）
const vehiclePlate = ref('')
const vehicleModel = ref('')
const vehicleColor = ref('')
const remark = ref('')
const loading = ref(false)
const toast = ref(false)
const toastMsg = ref('')

const stops = computed<TripStop[]>(() => {
  const list: TripStop[] = [{ city: fromCity.value }]
  viaCity.value.split(/[,，、]/).map((s) => s.trim()).filter(Boolean).forEach((c) => {
    list.push({ city: c })
  })
  list.push({ city: toCity.value })
  return list
})

const viaCount = computed(() => stops.value.length - 2)

async function submit() {
  if (!fromCity.value || !toCity.value) {
    toastMsg.value = '请填写出发和到达城市'
    toast.value = true
    return
  }
  if (!departureTime.value) {
    toastMsg.value = '请选择出发时间'
    toast.value = true
    return
  }
  if (new Date(departureTime.value).getTime() <= Date.now()) {
    toastMsg.value = '出发时间必须晚于当前时间'
    toast.value = true
    return
  }
  loading.value = true
  try {
    const pkm = Number(pricePerKm.value)
    await publishTrip({
      fromCity: fromCity.value.trim(),
      toCity: toCity.value.trim(),
      departureTime: departureTime.value,
      totalSeats: totalSeats.value,
      price: 40, // 兼容旧字段（后端区间计价优先 pricePerKm）
      pricePerKm: pkm > 0 ? pkm : undefined,
      stops: stops.value.length > 2 ? stops.value : undefined,
      vehiclePlate: vehiclePlate.value.trim() || undefined,
      vehicleModel: vehicleModel.value.trim() || undefined,
      vehicleColor: vehicleColor.value.trim() || undefined,
      remark: remark.value.trim() || undefined,
    })
    toastMsg.value = '行程发布成功'
    toast.value = true
    setTimeout(() => router.push('/driver/trips'), 800)
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="publish">
    <GNavBar title="发布行程" @back="router.back()" />

    <GCard class="publish__card">
      <h3 class="publish__section">路线</h3>
      <GInput v-model="fromCity" label="出发城市" placeholder="如：北京" />
      <GInput v-model="toCity" label="到达城市" placeholder="如：上海" />
      <GInput v-model="viaCity" label="途经城市（可选，逗号分隔）" placeholder="如：济南，南京" />
      <p v-if="viaCount > 0" class="publish__hint">已添加 {{ viaCount }} 个途经点，全程按站点区间计价</p>
    </GCard>

    <GCard class="publish__card">
      <h3 class="publish__section">时间与座位</h3>
      <GInput v-model="departureTime" type="datetime-local" label="出发时间" />
      <div class="publish__seats">
        <span>可拼座位数</span>
        <div class="publish__seat-btns">
          <button v-for="n in 6" :key="n" class="publish__seat" :class="{ 'publish__seat--on': totalSeats === n }" @click="totalSeats = n">{{ n }}</button>
        </div>
      </div>
      <GInput v-model="pricePerKm" label="每公里单价（元）" placeholder="如：0.35" />
      <p class="publish__hint">区间计价：乘客按「上车点→下车点」的实际里程 × 单价付款</p>
    </GCard>

    <GCard class="publish__card">
      <h3 class="publish__section">车辆信息（乘客下单前可见）</h3>
      <GInput v-model="vehiclePlate" label="车牌号" placeholder="如：京A·88888" />
      <GInput v-model="vehicleModel" label="车型" placeholder="如：特斯拉Model3" />
      <GInput v-model="vehicleColor" label="车身颜色" placeholder="如：白色" />
    </GCard>

    <GCard class="publish__card">
      <h3 class="publish__section">备注</h3>
      <GInput v-model="remark" label="备注（可选）" placeholder="如：顺路带人，可带小件" />
    </GCard>

    <div class="publish__footer safe-bottom">
      <GButton type="primary" block :loading="loading" @click="submit">发布行程</GButton>
    </div>

    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.publish {
  min-height: 100vh;
  padding-bottom: 90px;
}

.publish__card {
  margin: var(--sp-2);
}

.publish__section {
  font-size: var(--fs-14);
  font-weight: var(--fw-semibold);
  margin-bottom: var(--sp-2);
}

.publish__hint {
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-3);
}

.publish__seats {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-2);
  font-size: var(--fs-14);
  color: var(--text-1);
}

.publish__seat-btns {
  display: flex;
  gap: var(--sp-1);
}

.publish__seat {
  min-width: 36px;
  height: 36px;
  border-radius: var(--r-md);
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-1);
  font-size: var(--fs-14);
}

.publish__seat--on {
  background: var(--brand-grad);
  color: var(--on-brand);
  border-color: transparent;
}

.publish__footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: var(--sp-2);
  background: color-mix(in srgb, var(--bg-base) 78%, transparent);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  border-top: 1px solid var(--border);
}
</style>
