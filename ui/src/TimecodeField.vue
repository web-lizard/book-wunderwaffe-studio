<template>
  <input v-model="draft" type="text" @focus="focused = true" @change="$emit('commit', draft)"
         @blur="onBlur" @keydown.enter="$event.target.blur()" />
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
const props = defineProps({ value: { type: String, required: true } })
defineEmits(['commit'])
const draft = ref(props.value)
const focused = ref(false)
// Playback and async plan updates must not overwrite an unfinished timecode.
watch(() => props.value, value => { if (!focused.value) draft.value = value })
function onBlur() {
  focused.value = false
  nextTick(() => { draft.value = props.value })
}
</script>
