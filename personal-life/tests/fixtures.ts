// Synthetic adapter-contract fixtures, NOT a claimed Health Auto Export payload.
import type { SampleInput } from '../src/lib/domain';
export const sleep: SampleInput = {
  metricType: 'sleep_duration',
  value: 480,
  unit: 'min',
  startTime: '2026-10-10T23:30:00+08:00',
  endTime: '2026-10-11T07:30:00+08:00',
  source: 'Garmin',
  sourceRecordId: 'synthetic-sleep',
};
export const steps: SampleInput = {
  metricType: 'steps',
  value: 5000,
  unit: 'count',
  startTime: '2026-10-11T00:00:00+08:00',
  endTime: '2026-10-12T00:00:00+08:00',
  source: 'Garmin',
  sourceRecordId: 'synthetic-steps',
};
export const fixture = {
  format: 'normalized-v1',
  samples: [
    sleep,
    steps,
    { ...steps, source: 'iPhone', value: 6000, sourceRecordId: 'synthetic-other-source' },
    {
      metricType: 'sleep_stage',
      value: 20,
      unit: 'min',
      startTime: '2026-10-10T23:30:00+08:00',
      endTime: '2026-10-10T23:50:00+08:00',
      source: 'Garmin',
      stage: 'core',
    },
    {
      metricType: 'resting_heart_rate',
      value: 60,
      unit: 'bpm',
      startTime: '2026-10-11T07:00:00+08:00',
      endTime: '2026-10-11T07:00:00+08:00',
      source: 'Garmin',
    },
    {
      metricType: 'weight',
      value: 67,
      unit: 'kg',
      startTime: '2026-10-11T08:00:00+08:00',
      endTime: '2026-10-11T08:00:00+08:00',
      source: 'Manual test',
    },
  ],
  workouts: [
    {
      workoutType: '居家训练（测试）',
      startTime: '2026-10-11T09:00:00+08:00',
      endTime: '2026-10-11T09:20:00+08:00',
      durationMinutes: 20,
      calories: 80,
      source: 'Garmin',
    },
  ],
};
