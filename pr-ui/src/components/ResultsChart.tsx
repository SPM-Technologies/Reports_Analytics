import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useRef } from 'react';
import { formatColumnValue, formatValue } from '../format';
import type { ChartType, QueryResult } from '../types';

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

const PALETTE = ['#00707e', '#e8a33d', '#5b6fd8', '#d2604f', '#4f9d69', '#8d6cc4', '#c9a227', '#3e8ac9'];

interface Props {
  result: QueryResult;
  type: ChartType;
}

/** Whether a result can be charted: a summary with at least one group column and one measure. */
export function chartable(result: QueryResult): boolean {
  return result.mode === 'aggregate'
    && result.columns.some(c => c.role === 'dimension')
    && result.columns.some(c => c.role === 'measure');
}

/**
 * Bar / line: first group column on the X axis; one series per measure, or (with a second group column)
 * one series per value of that column for the first measure. Pie: first measure by first group column.
 */
export function ResultsChart({ result, type }: Props) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);
    const dims = result.columns.filter(c => c.role === 'dimension');
    const measures = result.columns.map((c, i) => ({ c, i })).filter(x => x.c.role === 'measure');
    const xIndex = result.columns.indexOf(dims[0]);
    // subtotal rows (meta column __level > 0) would be drawn on top of their own details
    const levelIndex = result.columns.findIndex(c => c.key === '__level');
    const rows = levelIndex < 0 ? result.rows : result.rows.filter(r => Number(r[levelIndex] ?? 0) === 0);
    const label = (v: unknown) => formatColumnValue(v, dims[0]);
    const categories = [...new Set(rows.map(r => label(r[xIndex])))];

    let option: echarts.EChartsCoreOption;
    if (type === 'pie') {
      const m = measures[0];
      option = {
        color: PALETTE,
        tooltip: { trigger: 'item', valueFormatter: (v: unknown) => formatValue(v, m.c.type, 'measure') },
        legend: { type: 'scroll', orient: 'vertical', right: 0, top: 'middle' },
        series: [{ type: 'pie', radius: ['35%', '70%'], center: ['40%', '50%'], name: m.c.label,
          data: rows.map(r => ({ name: label(r[xIndex]), value: Number(r[m.i]) })) }],
      };
    } else {
      let series;
      if (dims.length > 1) {
        const sIndex = result.columns.indexOf(dims[1]);
        const m = measures[0];
        const groups = [...new Set(rows.map(r => formatColumnValue(r[sIndex], dims[1])))];
        series = groups.map(g => ({
          name: g, type, stack: type === 'bar' ? 'total' : undefined, smooth: type === 'line',
          data: categories.map(cat => {
            const row = rows.find(r => label(r[xIndex]) === cat && formatColumnValue(r[sIndex], dims[1]) === g);
            return row ? Number(row[m.i]) : 0;
          }),
        }));
      } else {
        series = measures.map((m, k) => ({
          name: m.c.label, type, smooth: type === 'line', yAxisIndex: k > 0 && measures.length === 2 ? 1 : 0,
          data: categories.map(cat => {
            const row = rows.find(r => label(r[xIndex]) === cat);
            return row ? Number(row[m.i]) : 0;
          }),
        }));
      }
      const twoAxes = dims.length === 1 && measures.length === 2;
      option = {
        color: PALETTE,
        tooltip: { trigger: 'axis' },
        legend: { type: 'scroll', top: 0 },
        grid: { left: 8, right: twoAxes ? 8 : 16, top: 40, bottom: 8, containLabel: true },
        xAxis: { type: 'category', data: categories, axisLabel: { rotate: categories.length > 8 ? 35 : 0 } },
        yAxis: twoAxes
          ? [{ type: 'value', name: measures[0].c.label }, { type: 'value', name: measures[1].c.label }]
          : [{ type: 'value' }],
        series,
      };
    }
    chart.setOption(option);
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      chart.dispose();
    };
  }, [result, type]);

  return <div ref={ref} className="chart" role="img" aria-label={`${type} chart of the report`} />;
}
