import json, sys, math
import numpy as np
from scipy.stats import spearmanr
scr = sys.argv[1]
R = json.load(open(f'{scr}/sharpness-results.json'))['results']
metrics = ['app_global', 'app_center', 'app_tile_max', 'app_tile_median', 'qr_lapvar_1024', 'qr_lapvar_full', 'qr_lapvar_norm', 'qr_tenengrad_full', 'qr_tenengrad_norm', 'qr_lapvar_minus_noise', 'edge_width_marziliano', 'sfr_width10_90', 'sfr_mtf50']
higher_is_sharper = {m: not m.endswith('width') and 'width' not in m for m in metrics}
deltas = sorted({r['delta'] for r in R})
def at(light, delta, m, idx=None):
    v = [r[m] for r in R if r['light'] == light and r['delta'] == delta and (idx is None or r['index'] == idx)]
    return float(np.mean(v)) if v else float('nan')
print('--- reference check: best focus measured twice (start and end of each series), mtf50 / app_global')
for light in ('dark', 'lit'):
    a = [r for r in R if r['light'] == light and r['index'] == 6]; b = [r for r in R if r['light'] == light and r['index'] == 12]
    print(light, 'start', round(np.mean([x['sfr_mtf50'] for x in a]), 3), round(np.mean([x['app_global'] for x in a]), 1), ' end', round(np.mean([x['sfr_mtf50'] for x in b]), 3), round(np.mean([x['app_global'] for x in b]), 1))
print('\n--- exposure/ISO per light:', {l: sorted({(r['exposure'], r['iso']) for r in R if r['light'] == l}) for l in ('dark', 'lit')})
print('--- blank-paper noise floor (Laplacian variance, full res), median over shots: dark', round(np.median([r['blank_lapvar'] for r in R if r['light'] == 'dark']), 2), ' lit', round(np.median([r['blank_lapvar'] for r in R if r['light'] == 'lit']), 2))
for m in ('sfr_mtf50', 'sfr_width10_90', 'app_global', 'qr_lapvar_full', 'qr_lapvar_norm', 'edge_width_marziliano'):
    print(f'\n=== {m}: mean of 3 shots per lens offset (diopters from best focus)')
    print('delta   ' + ' '.join(f'{d:+6.2f}' for d in sorted(set(deltas))))
    for light in ('dark', 'lit'):
        print(f'{light:6}  ' + ' '.join(f'{at(light, d, m):6.2f}' if abs(at(light, d, m)) < 1000 else f'{at(light, d, m):6.0f}' for d in sorted(set(deltas))))
print('\n--- decode success of the 3 QR codes (full res / 1024 px copy), mean over shots')
for light in ('dark', 'lit'):
    print(light, ' '.join(f'{d:+.2f}:{np.mean([r["qr_decoded_full"] for r in R if r["light"]==light and r["delta"]==d]):.1f}/{np.mean([r["qr_decoded_1024"] for r in R if r["light"]==light and r["delta"]==d]):.1f}' for d in sorted(set(deltas))))
# --- evaluation of each metric
print('\n=== EVALUATION (per metric)')
print('rho_truth  = Spearman with the physical truth (MTF50), all 78 shots (both lights pooled)')
print('rho_defocus= Spearman with -|offset|, pooled; light_ratio = value at best focus lit/dark (1.0 = light-proof)')
print('range_03 / range_06 = best-focus value divided by value at |offset| 0.3 / 0.6 D (how loudly the metric reacts)')
print('cv_repeat  = coefficient of variation over the 3 repeated shots at best focus (%)')
rows = []
truth = np.array([r['sfr_mtf50'] for r in R])
for m in metrics:
    v = np.array([r[m] for r in R]); sign = 1 if higher_is_sharper[m] else -1
    rt = spearmanr(sign * v, truth, nan_policy='omit')[0]
    off = np.array([-abs(r['delta']) for r in R]); rd = spearmanr(sign * v, off, nan_policy='omit')[0]
    best = lambda light: np.mean([r[m] for r in R if r['light'] == light and r['delta'] == 0.0])
    ratio = best('lit') / best('dark')
    def rng(dm):
        b = np.mean([best('dark'), best('lit')]); o = np.mean([at(l, s, m) for l in ('dark', 'lit') for s in (-dm, dm) if not math.isnan(at(l, s, m))])
        return (b / o) if higher_is_sharper[m] else (o / b)
    cv = np.mean([np.std([r[m] for r in R if r['light'] == l and r['delta'] == 0.0 and r['index'] == 6]) / abs(np.mean([r[m] for r in R if r['light'] == l and r['delta'] == 0.0 and r['index'] == 6])) * 100 for l in ('dark', 'lit')])
    rows.append((m, rt, rd, ratio, rng(0.3), rng(0.6), cv))
print(f'{"metric":26}{"rho_truth":>10}{"rho_defocus":>12}{"light_ratio":>12}{"range_03":>9}{"range_06":>9}{"cv_repeat%":>11}')
for m, rt, rd, ratio, r3, r6, cv in sorted(rows, key=lambda x: -abs(x[1])):
    print(f'{m:26}{rt:10.3f}{rd:12.3f}{ratio:12.2f}{r3:9.2f}{r6:9.2f}{cv:11.2f}')
json.dump([dict(zip(('metric', 'rho_truth', 'rho_defocus', 'light_ratio', 'range_03', 'range_06', 'cv_repeat_percent'), r)) for r in rows], open(f'{scr}/sharpness-summary.json', 'w'), indent=1)
