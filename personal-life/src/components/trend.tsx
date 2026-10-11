'use client';
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  BarChart,
  Bar,
} from 'recharts';
export default function Trend({
  data,
  field,
  label,
  unit,
  bar = false,
}: {
  data: Record<string, unknown>[];
  field: string;
  label: string;
  unit: string;
  bar?: boolean;
}) {
  if (!data.some((d) => d[field] != null))
    return <div className="empty">暂无{label}记录，完成同步后会显示趋势。</div>;
  const grid = <CartesianGrid stroke="#263445" strokeDasharray="3 3" />,
    x = <XAxis dataKey="date" tick={{ fill: '#9aaec2', fontSize: 11 }} />,
    y = <YAxis tick={{ fill: '#9aaec2', fontSize: 11 }} width={45} />;
  return (
    <div className="chart" role="img" aria-label={`${label}趋势，单位${unit}`}>
      <ResponsiveContainer width="100%" height={250}>
        {bar ? (
          <BarChart data={data}>
            {grid}
            {x}
            {y}
            <Tooltip
              contentStyle={{
                background: '#12202e',
                border: '1px solid #314152',
                borderRadius: 12,
              }}
            />
            <Bar dataKey={field} name={label} unit={unit} fill="#5ad8ad" radius={[4, 4, 0, 0]} />
          </BarChart>
        ) : (
          <LineChart data={data}>
            {grid}
            {x}
            {y}
            <Tooltip
              contentStyle={{
                background: '#12202e',
                border: '1px solid #314152',
                borderRadius: 12,
              }}
            />
            <Line
              dataKey={field}
              name={label}
              unit={unit}
              stroke="#5ad8ad"
              strokeWidth={2}
              dot={false}
              connectNulls={false}
            />
          </LineChart>
        )}
      </ResponsiveContainer>
    </div>
  );
}
