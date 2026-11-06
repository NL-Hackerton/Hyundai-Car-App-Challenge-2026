import React from 'react';
import {AbsoluteFill, interpolate, Sequence, useCurrentFrame} from 'remotion';
import {C, DISCLOSURE, fontBase, FPS} from '../theme';
import {activeAnnotation, sectionFrames, type TimelineSection} from '../timeline';
import {ChargeCurve, socAt} from '../components/charts';
import {DeviceFrame, Footage, FootageBadge} from '../components/device';
import {FineBars, HourTimer, MergingChips} from '../components/facts';
import {Wordmark} from '../components/brand';
import {clamp, EASE, EASE_IN_OUT, Rise, SceneFade, useSpring, WipeLine} from '../components/motion';

const M = 140; // outer margin

const Source: React.FC<{children: React.ReactNode; delay?: number}> = ({children, delay = 30}) => (
  <Rise delay={delay} y={10}>
    <div style={{...fontBase, fontSize: 24, fontWeight: 500, color: C.muted, display: 'flex', gap: 12}}>
      <span style={{color: C.sub, fontWeight: 600}}>출처</span>
      <span>{children}</span>
    </div>
  </Rise>
);

/* ───────────── 1. Hook ───────────── */
export const HOOK = 240;
export const Hook: React.FC = () => {
  const frame = useCurrentFrame();
  const t = interpolate(frame, [20, HOOK - 20], [0.38, 0.62], {...clamp, easing: EASE_IN_OUT});
  const soc = socAt(t);
  const barL = M;
  const barW = 1920 - M * 2;
  const barIn = useSpring(10);
  return (
    <SceneFade duration={HOOK} fadeIn={10}>
      <AbsoluteFill style={{...fontBase, padding: `0 ${M}px`, justifyContent: 'center'}}>
        <div style={{marginTop: -120}}>
          <WipeLine delay={8} style={{fontSize: 124, fontWeight: 800, letterSpacing: '-0.045em', lineHeight: 1.12}}>
            충전기 꽂고 기다릴까,
          </WipeLine>
          <WipeLine delay={20} style={{fontSize: 124, fontWeight: 800, letterSpacing: '-0.045em', lineHeight: 1.12}}>
            지금 떠날까?
          </WipeLine>
          <Rise delay={46} style={{marginTop: 44}}>
            <div style={{fontSize: 40, fontWeight: 500, color: C.sub, letterSpacing: '-0.015em'}}>
              고속도로 휴게소 급속충전 중, 매번 다시 하는 계산
            </div>
          </Rise>
        </div>
      </AbsoluteFill>
      {/* charging bar motif */}
      <div style={{position: 'absolute', left: barL, top: 860, width: barW, opacity: barIn, ...fontBase}}>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 22}}>
          <div style={{fontSize: 26, fontWeight: 600, color: C.sub, display: 'flex', gap: 14, alignItems: 'center'}}>
            <span
              style={{
                width: 12,
                height: 12,
                borderRadius: 99,
                background: C.chargeLight,
                opacity: 0.55 + 0.45 * Math.abs(Math.sin(frame / 14)),
              }}
            />
            급속충전 중
          </div>
          <div style={{fontSize: 44, fontWeight: 800, letterSpacing: '-0.02em', fontVariantNumeric: 'tabular-nums'}}>
            {Math.round(soc * 100)}%
          </div>
        </div>
        <div style={{position: 'relative', height: 10, borderRadius: 99, background: C.line}}>
          <div
            style={{
              position: 'absolute',
              left: 0,
              top: 0,
              bottom: 0,
              width: `${soc * 100}%`,
              borderRadius: 99,
              background: `linear-gradient(90deg, rgba(47,158,98,0.4), ${C.chargeLight})`,
              boxShadow: '0 0 24px rgba(76,194,131,0.45)',
            }}
          />
          <div style={{position: 'absolute', left: '80%', top: -10, width: 2, height: 30, background: C.sub, opacity: 0.7}} />
          <div style={{position: 'absolute', left: '80%', top: 30, transform: 'translateX(-50%)', fontSize: 22, color: C.muted, fontWeight: 600}}>
            80%
          </div>
        </div>
      </div>
    </SceneFade>
  );
};

/* ───────────── 2. Problem ───────────── */
export const FACT = 165;
export const PROBLEM = FACT * 4;

const FactLayout: React.FC<{
  lines: string[];
  source?: string;
  visual: React.ReactNode;
}> = ({lines, source, visual}) => (
  <SceneFade duration={FACT} fadeIn={12} fadeOut={12}>
    <AbsoluteFill style={{...fontBase, flexDirection: 'row', alignItems: 'center', padding: `0 ${M}px`}}>
      <div style={{width: 820, display: 'flex', flexDirection: 'column', gap: 0}}>
        {lines.map((l, i) => (
          <WipeLine
            key={i}
            delay={6 + i * 8}
            style={{fontSize: 68, fontWeight: 700, letterSpacing: '-0.035em', lineHeight: 1.26}}
          >
            {l}
          </WipeLine>
        ))}
        {source && <div style={{marginTop: 48}}><Source delay={34}>{source}</Source></div>}
      </div>
      <div style={{flex: 1, display: 'flex', justifyContent: 'flex-end', alignItems: 'center'}}>{visual}</div>
    </AbsoluteFill>
  </SceneFade>
);

export const Problem: React.FC = () => (
  <AbsoluteFill>
    <Sequence durationInFrames={FACT}>
      <FactLayout
        lines={['80%를 넘으면', '충전 속도가 느려진다']}
        source="현대자동차그룹 저널(2022)"
        visual={<ChargeCurve width={760} height={520} start={14} drawFrames={90} />}
      />
    </Sequence>
    <Sequence from={FACT} durationInFrames={FACT}>
      <FactLayout
        lines={['급속충전구역 1시간 초과 주차는', '충전 방해행위']}
        source="환경친화적 자동차법 시행령 제18조의8"
        visual={<HourTimer size={520} start={16} />}
      />
    </Sequence>
    <Sequence from={FACT * 2} durationInFrames={FACT}>
      <FactLayout
        lines={['충전구역 과태료 부과', '3년 새 5배 넘게 늘었다']}
        source="연합뉴스TV(2026)"
        visual={<FineBars width={700} height={560} start={16} />}
      />
    </Sequence>
    <Sequence from={FACT * 3} durationInFrames={FACT}>
      <FactLayout
        lines={['차량 화면·내비·충전 앱에', '흩어진 정보를', '운전자가 머릿속에서 조합한다']}
        visual={<MergingChips width={720} height={560} start={10} />}
      />
    </Sequence>
  </AbsoluteFill>
);

/* ───────────── 3. Solution ───────────── */
export const SOLUTION = 360;
const INPUTS = ['차량 SOC·충전전력', '내비 도착 예상 SOC', '국내 점유 규칙'];

export const Solution: React.FC = () => {
  const frame = useCurrentFrame();
  const lineP = interpolate(frame, [150, 200], [0, 1], {...clamp, easing: EASE_IN_OUT});
  const out = useSpring(196);
  return (
    <SceneFade duration={SOLUTION}>
      <AbsoluteFill style={{...fontBase, padding: `0 ${M}px`, justifyContent: 'center'}}>
        <div style={{marginTop: -60}}>
          <Wordmark size={150} delay={6} />
          <Rise delay={30} style={{marginTop: 40}}>
            <div style={{fontSize: 52, fontWeight: 600, letterSpacing: '-0.03em', color: C.text}}>
              충전 중에 차가 먼저 알려주는 Stay·Go
            </div>
          </Rise>
        </div>
      </AbsoluteFill>
      {/* inputs → decision */}
      <div style={{position: 'absolute', left: M, top: 780, right: M, height: 120, ...fontBase}}>
        <div style={{display: 'flex', gap: 24, alignItems: 'center', height: '100%'}}>
          {INPUTS.map((t, i) => (
            <Rise key={t} delay={90 + i * 14} y={18}>
              <div
                style={{
                  padding: '22px 32px',
                  borderRadius: 16,
                  border: `1px solid ${C.lineStrong}`,
                  background: C.panel,
                  fontSize: 32,
                  fontWeight: 600,
                  whiteSpace: 'nowrap',
                }}
              >
                {t}
              </div>
            </Rise>
          ))}
          <div style={{flex: 1, height: 3, position: 'relative', margin: '0 28px'}}>
            <div
              style={{
                position: 'absolute',
                inset: 0,
                width: `${lineP * 100}%`,
                background: `linear-gradient(90deg, ${C.lineStrong}, ${C.goLight})`,
                borderRadius: 2,
              }}
            />
          </div>
          <div style={{display: 'flex', gap: 14, opacity: out, transform: `translateX(${(1 - out) * 20}px)`}}>
            <div style={{padding: '22px 30px', borderRadius: 16, border: `2px solid ${C.sub}`, fontSize: 34, fontWeight: 800}}>
              Stay
            </div>
            <div style={{padding: '22px 34px', borderRadius: 16, background: C.go, fontSize: 34, fontWeight: 800}}>
              Go
            </div>
          </div>
        </div>
      </div>
    </SceneFade>
  );
};

/* ───────────── 4. Live demo ───────────── */
const SCREEN_W = 1440;
const DEV_X = 56;
const DEV_Y = 72;
const DEV_H = Math.round((SCREEN_W * 9) / 16) + 32;

const StepCircle: React.FC<{n: number; active: boolean; done: boolean; size: number}> = ({
  n,
  active,
  done,
  size,
}) => (
  <div
    style={{
      width: size,
      height: size,
      borderRadius: 99,
      flex: `0 0 ${size}px`,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: size * 0.46,
      fontWeight: 800,
      fontVariantNumeric: 'tabular-nums',
      background: active ? C.go : done ? 'rgba(91,155,234,0.16)' : 'transparent',
      border: `2px solid ${active ? C.go : done ? 'rgba(91,155,234,0.5)' : C.lineStrong}`,
      color: active ? '#fff' : done ? C.goLight : C.muted,
    }}
  >
    {n}
  </div>
);

export const LiveDemo: React.FC<{section: TimelineSection}> = ({section}) => {
  const frame = useCurrentFrame();
  const total = sectionFrames(section);
  const sec = frame / FPS;
  const anns = section.annotations;
  const idx = activeAnnotation(anns, sec);
  const cur = anns[idx];
  const since = cur ? frame - Math.round(cur.at * FPS) : frame;
  const capIn = interpolate(since, [0, 14], [0, 1], {...clamp, easing: EASE});
  const devIn = useSpring(0, 200, 30);
  const railIn = useSpring(14);
  const prog = frame / total;
  const ITEM = DEV_H / Math.max(anns.length, 1);
  return (
    <SceneFade duration={total} fadeIn={14} fadeOut={14}>
      <div style={{position: 'absolute', left: DEV_X, top: DEV_Y, transform: `scale(${0.965 + devIn * 0.035})`, transformOrigin: '50% 50%', opacity: devIn}}>
        <DeviceFrame screenWidth={SCREEN_W}>
          <Footage section={section} screenWidth={SCREEN_W} />
        </DeviceFrame>
      </div>
      <div style={{position: 'absolute', left: DEV_X + 8, top: 22}}>
        <FootageBadge section={section} size={22} />
      </div>
      {/* lower third: current step */}
      {cur && (
        <div
          style={{
            ...fontBase,
            position: 'absolute',
            left: DEV_X + 8,
            top: DEV_Y + DEV_H + 30,
            width: SCREEN_W,
            display: 'flex',
            alignItems: 'center',
            gap: 26,
            opacity: capIn,
            transform: `translateY(${(1 - capIn) * 12}px)`,
          }}
        >
          <StepCircle n={idx + 1} active done={false} size={60} />
          <div style={{fontSize: 44, fontWeight: 800, letterSpacing: '-0.03em', whiteSpace: 'nowrap'}}>{cur.title}</div>
          <div style={{width: 2, height: 36, background: C.lineStrong}} />
          <div style={{fontSize: 34, fontWeight: 500, color: C.sub, letterSpacing: '-0.02em', whiteSpace: 'nowrap'}}>
            {cur.body}
          </div>
        </div>
      )}
      {/* step rail */}
      <div
        style={{
          ...fontBase,
          position: 'absolute',
          left: DEV_X + SCREEN_W + 32 + 48,
          top: DEV_Y,
          width: 1920 - (DEV_X + SCREEN_W + 32 + 48) - 40,
          height: DEV_H,
          opacity: railIn,
        }}
      >
        <div style={{position: 'absolute', left: 21, top: ITEM / 2, height: DEV_H - ITEM, width: 2, background: C.line}} />
        <div style={{position: 'absolute', left: 21, top: ITEM / 2, height: (DEV_H - ITEM) * Math.min(1, prog * 1.0), width: 2, background: C.goLight}} />
        {anns.map((a, i) => {
          const active = i === idx;
          const done = i < idx;
          return (
            <div
              key={i}
              style={{
                position: 'absolute',
                top: i * ITEM + ITEM / 2 - 22,
                left: 0,
                right: 0,
                display: 'flex',
                alignItems: 'center',
                gap: 16,
              }}
            >
              <StepCircle n={i + 1} active={active} done={done} size={44} />
              <div
                style={{
                  fontSize: 26,
                  fontWeight: active ? 700 : 500,
                  color: active ? C.text : done ? C.sub : C.muted,
                  letterSpacing: '-0.02em',
                  whiteSpace: 'nowrap',
                }}
              >
                {a.title}
              </div>
            </div>
          );
        })}
      </div>
    </SceneFade>
  );
};

/* ───────────── 5. How it works ───────────── */
export const HOW = 540;
const SOURCES = [
  {t: 'Vehicle SDK', d: 'SOC'},
  {t: 'AAOS 차량 속성', d: '배터리 잔량 · 충전 상태'},
  {t: 'NaviHelper', d: 'requestTrip → 도착 예상 SOC, 경유 충전지'},
  {t: 'CarUxRestrictions', d: '주행 중 조작 제한'},
];

export const HowItWorks: React.FC = () => {
  const frame = useCurrentFrame();
  const SX = M;
  const SW = 620;
  const SH = 124;
  const GAP = 30;
  const TOP = 300;
  const MX = 1010;
  const MW = 380;
  const MH = 230;
  const MY = TOP + (SOURCES.length * SH + (SOURCES.length - 1) * GAP) / 2 - MH / 2;
  const OX = 1560;
  const OW = 220;
  const OH = 110;
  const modelIn = useSpring(110);
  const outIn = [useSpring(190), useSpring(202)];
  const pulse = (frame - 240) / 60;
  const outs = [
    {t: 'Stay', y: MY + MH / 2 - OH - 18},
    {t: 'Go', y: MY + MH / 2 + 18},
  ];
  return (
    <SceneFade duration={HOW}>
      <div style={{...fontBase, position: 'absolute', left: M, top: 120}}>
        <WipeLine delay={4} style={{fontSize: 56, fontWeight: 700, letterSpacing: '-0.035em'}}>
          차량 신호와 내비 계산을 하나의 판단으로
        </WipeLine>
      </div>
      <svg width={1920} height={1080} style={{position: 'absolute', inset: 0}}>
        {SOURCES.map((_, i) => {
          const y0 = TOP + i * (SH + GAP) + SH / 2;
          const x0 = SX + SW;
          const y1 = MY + MH / 2 + (i - 1.5) * 34;
          const x1 = MX;
          const s = 60 + i * 12;
          const p = interpolate(frame, [s, s + 50], [0, 1], {...clamp, easing: EASE_IN_OUT});
          const d = `M${x0},${y0} C${x0 + 160},${y0} ${x1 - 170},${y1} ${x1},${y1}`;
          const ph = ((pulse + i * 0.23) % 1 + 1) % 1;
          return (
            <g key={i}>
              <path d={d} fill="none" stroke={C.lineStrong} strokeWidth={2.5} pathLength={1} strokeDasharray="1 1" strokeDashoffset={1 - p} />
              {frame > 240 && (
                <path d={d} fill="none" stroke={C.goLight} strokeWidth={3} pathLength={1}
                  strokeDasharray="0.08 0.92" strokeDashoffset={-ph} strokeLinecap="round" opacity={0.9} />
              )}
            </g>
          );
        })}
        {outs.map((o, i) => {
          const x0 = MX + MW;
          const y0 = MY + MH / 2;
          const x1 = OX;
          const y1 = o.y + OH / 2;
          const s = 160 + i * 10;
          const p = interpolate(frame, [s, s + 40], [0, 1], {...clamp, easing: EASE_IN_OUT});
          const d = `M${x0},${y0} C${x0 + 80},${y0} ${x1 - 80},${y1} ${x1},${y1}`;
          return <path key={o.t} d={d} fill="none" stroke={i === 1 ? C.goLight : C.sub} strokeWidth={2.5} pathLength={1} strokeDasharray="1 1" strokeDashoffset={1 - p} />;
        })}
      </svg>
      {SOURCES.map((s, i) => (
        <div key={s.t} style={{position: 'absolute', left: SX, top: TOP + i * (SH + GAP)}}>
          <Rise delay={20 + i * 10} y={16}>
            <div
              style={{
                ...fontBase,
                width: SW,
                height: SH,
                boxSizing: 'border-box',
                padding: '0 32px',
                borderRadius: 18,
                background: C.panel,
                border: `1px solid ${C.line}`,
                display: 'flex',
                flexDirection: 'column',
                justifyContent: 'center',
                gap: 6,
              }}
            >
              <div style={{fontSize: 34, fontWeight: 700, letterSpacing: '-0.02em'}}>{s.t}</div>
              <div style={{fontSize: 25, fontWeight: 500, color: C.sub, whiteSpace: 'nowrap'}}>{s.d}</div>
            </div>
          </Rise>
        </div>
      ))}
      <div
        style={{
          ...fontBase,
          position: 'absolute',
          left: MX,
          top: MY,
          width: MW,
          height: MH,
          borderRadius: 26,
          background: 'linear-gradient(160deg, #18253f, #101a2e)',
          border: `2px solid rgba(91,155,234,0.55)`,
          boxShadow: `0 0 ${40 + 20 * Math.sin(frame / 18)}px rgba(31,111,209,0.22)`,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 12,
          opacity: modelIn,
          transform: `scale(${0.92 + modelIn * 0.08})`,
        }}
      >
        <div style={{fontSize: 44, fontWeight: 800, letterSpacing: '-0.03em'}}>결정 모델</div>
        <div style={{fontSize: 28, fontWeight: 500, color: C.sub}}>규칙 6개</div>
      </div>
      {outs.map((o, i) => (
        <div
          key={o.t}
          style={{
            ...fontBase,
            position: 'absolute',
            left: OX,
            top: o.y,
            width: OW,
            height: OH,
            borderRadius: 20,
            boxSizing: 'border-box',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 42,
            fontWeight: 800,
            background: i === 1 ? C.go : 'transparent',
            border: i === 1 ? 'none' : `2px solid ${C.sub}`,
            opacity: outIn[i],
            transform: `translateX(${(1 - outIn[i]) * 18}px)`,
          }}
        >
          {o.t}
        </div>
      ))}
    </SceneFade>
  );
};

/* ───────────── 6. Outro ───────────── */
export const OUTRO = 300;
export const Outro: React.FC = () => (
  <SceneFade duration={OUTRO} fadeOut={24}>
    <AbsoluteFill style={{...fontBase, padding: `0 ${M}px`, justifyContent: 'center'}}>
      <div style={{marginTop: -80}}>
        <WipeLine delay={6} style={{fontSize: 84, fontWeight: 800, letterSpacing: '-0.04em', lineHeight: 1.2}}>
          충전 후반, 떠날 때를
        </WipeLine>
        <WipeLine delay={16} style={{fontSize: 84, fontWeight: 800, letterSpacing: '-0.04em', lineHeight: 1.2}}>
          차가 먼저 알려준다
        </WipeLine>
        <div style={{marginTop: 90, display: 'flex', alignItems: 'center', gap: 48}}>
          <Wordmark size={72} delay={50} />
          <Rise delay={64}>
            <div style={{fontSize: 32, fontWeight: 500, color: C.sub, borderLeft: `2px solid ${C.lineStrong}`, paddingLeft: 40}}>
              Pleos Connect 차량용 앱
            </div>
          </Rise>
        </div>
      </div>
    </AbsoluteFill>
    <div style={{position: 'absolute', left: M, bottom: 72}}>
      <Rise delay={80} y={8}>
        <div style={{...fontBase, fontSize: 24, fontWeight: 500, color: C.muted}}>{DISCLOSURE}</div>
      </Rise>
    </div>
  </SceneFade>
);
