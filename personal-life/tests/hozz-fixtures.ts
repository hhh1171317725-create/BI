// Synthetic schema-v1 records only. No real device identifiers or personal measurements.
export function hozzRecord(
  id: string,
  type: string,
  kind = 'quantity',
  extra: Record<string, unknown> = {},
) {
  return {
    schemaVersion: 1,
    catalogVersion: 6,
    canonicalId: 'apple.healthkit:' + id,
    recordVersion: 1,
    id,
    type,
    kind,
    sourceRecord: { store: 'apple.healthkit', id, type },
    source: { name: 'Garmin Connect', bundleIdentifier: 'test.synthetic.garmin' },
    startDate: '2026-10-08T08:00:00+08:00',
    endDate: '2026-10-08T09:00:00+08:00',
    ...extra,
  };
}
export const hozzSleep = (id: string, value: number, startDate: string, endDate: string) =>
  hozzRecord(id, 'HKCategoryTypeIdentifierSleepAnalysis', 'category', {
    value,
    startDate,
    endDate,
  });
export const hozzFixture = [
  hozzRecord('steps', 'HKQuantityTypeIdentifierStepCount', 'quantity', {
    quantity: { value: 1000, unit: 'count' },
  }),
  hozzRecord('resting', 'HKQuantityTypeIdentifierRestingHeartRate', 'quantity', {
    quantity: { value: 60, unit: 'count/min' },
  }),
  hozzRecord('energy', 'HKQuantityTypeIdentifierActiveEnergyBurned', 'quantity', {
    quantity: { value: 418.4, unit: 'kJ' },
  }),
  hozzRecord('weight', 'HKQuantityTypeIdentifierBodyMass', 'quantity', {
    quantity: { value: 150, unit: 'lb' },
  }),
  hozzRecord('run', 'HKWorkoutTypeIdentifier', 'workout', { activityType: 37, duration: 1800 }),
  hozzSleep('bed', 0, '2026-10-07T23:00:00+08:00', '2026-10-08T08:00:00+08:00'),
  hozzSleep('core', 3, '2026-10-07T23:30:00+08:00', '2026-10-08T07:00:00+08:00'),
  hozzSleep('deep', 4, '2026-10-08T00:30:00+08:00', '2026-10-08T01:30:00+08:00'),
  hozzSleep('awake', 2, '2026-10-08T02:00:00+08:00', '2026-10-08T02:15:00+08:00'),
];
