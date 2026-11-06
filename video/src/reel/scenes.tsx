import React from 'react';
import {AbsoluteFill, interpolate, Sequence, useCurrentFrame} from 'remotion';
import {C, DISCLOSURE, fontBase, FPS} from '../theme';
import {activeAnnotation, sectionFrames, type TimelineSection} from '../timeline';
import {ChargeCurve, socAt} from '../components/charts';
import {DeviceFrame, Footage, FootageBadge, sectionAspect} from '../components/device';
import {HourTimer} from '../components/facts';
import {Wordmark} from '../components/brand';
import {clamp, EASE, EASE_IN_OUT, Rise, SceneFade, useSpring, WipeLine} from '../components/motion';

// Safe area for Reels / Shorts overlays: keep key text between these lines.
export const SAFE_TOP = 220;
export const SAFE_BOTTOM = 1920 - 380;
const M = 84;

export const R_HOOK = 90;
export const ReelHook: React.FC = () => {
  const frame = useCurrentFrame();
  const soc = socAt(interpolate(frame, [0, R_HOOK], [0.4, 0.58], {...clamp, easing: EASE_IN_OUT}));
  const barIn = useSpring(14);
  return (
    <SceneFade duration={R_HOOK} fadeIn={6} fadeOut={10}>
      <AbsoluteFill style={{...fontBase, padding: `0 ${M}px`, justifyContent: 'center'}}>
        <div style={{marginTop: -140}}>
          <WipeLine delay={2} style={{fontSize: 104, fontWeight: 800, letterSpacing: '-0.045em', lineHeight: 1.18}}>
            휴게소 충전 중,
          </WipeLine>
          <WipeLine delay={10} style={{fontSize: 104, fontWeight: 800, letterSpacing: '-0.045em', lineHeight: 1.18}}>
            언제 떠나야
          </WipeLine>
          <WipeLine delay={16} style={{fontSize: 104, fontWeight: 800, letterSpacing: '-0.045em', lineHeight: 1.18}}>
            할까?
          </WipeLine>
        </div>
      </AbsoluteFill>
      <div style={{...fontBase, position: 'absolute', left: M, right: M, top: 1240, opacity: barIn}}>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 20}}>
          <div style={{fontSize: 34, fontWeight: 600, color: C.sub}}>급속충전 중</div>
          <div style={{fontSize: 56, fontWeight: 800, fontVariantNumeric: 'tabular-nums'}}>{Math.round(soc * 100)}%</div>
        </div>
        <div style={{position: 'relative', height: 14, borderRadius: 99, background: C.line}}>
          <div
            style={{
              position: 'absolute',
              inset: 0,
              width: `${soc * 100}%`,
              borderRadius: 99,
              background: `linear-gradient(90deg, rgba(47,158,98,0.4), ${C.chargeLight})`,
              boxShadow: '0 0 24px rgba(76,194,131,0.45)',
            }}
          />
          <div style={{position: 'absolute', left: '80%', top: -10, width: 3, height: 34, background: C.sub, opacity: 0.7}} />
        </div>
      </div>
    </SceneFade>
  );
};

const R_FACT = 120;
export const R_PROBLEM = R_FACT * 2;

const ReelFact: React.FC<{lines: string[]; source: string; visual: React.ReactNode}> = ({
  lines,
  source,
  visual,
}) => (
  <SceneFade duration={R_FACT} fadeIn={8} fadeOut={10}>
    <div style={{...fontBase, position: 'absolute', left: M, right: M, top: 360}}>
      {lines.map((l, i) => (
        <WipeLine key={i} delay={2 + i * 6} style={{fontSize: 84, fontWeight: 800, letterSpacing: '-0.04em', lineHeight: 1.22}}>
          {l}
        </WipeLine>
      ))}
    </div>
    <div style={{position: 'absolute', left: 0, right: 0, top: 760, display: 'flex', justifyContent: 'center'}}>
      {visual}
    </div>
    <div style={{...fontBase, position: 'absolute', left: M, top: SAFE_BOTTOM - 70}}>
      <Rise delay={20} y={8}>
        <div style={{fontSize: 30, fontWeight: 500, color: C.muted}}>
          <span style={{color: C.sub, fontWeight: 600, marginRight: 14}}>출처</span>
          {source}
        </div>
      </Rise>
    </div>
  </SceneFade>
);

export const ReelProblem: React.FC = () => (
  <AbsoluteFill>
    <Sequence durationInFrames={R_FACT}>
      <ReelFact
        lines={['80%를 넘으면', '충전이 느려진다']}
        source="현대자동차그룹 저널(2022)"
        visual={<ChargeCurve width={900} height={560} start={8} drawFrames={70} />}
      />
    </Sequence>
    <Sequence from={R_FACT} durationInFrames={R_FACT}>
      <ReelFact
        lines={['1시간을 넘기면', '충전 방해행위']}
        source="환경친화적 자동차법 시행령 제18조의8"
        visual={<HourTimer size={600} start={8} />}
      />
    </Sequence>
  </AbsoluteFill>
);

const SCREEN_W = 1000;
const DEV_W = SCREEN_W + 32;

export const ReelDemo: React.FC<{section: TimelineSection}> = ({section}) => {
  const frame = useCurrentFrame();
  const total = sectionFrames(section);
  const anns = section.annotations;
  const idx = activeAnnotation(anns, frame / FPS);
  const cur = anns[idx];
  const since = cur ? frame - Math.round(cur.at * FPS) : frame;
  const capIn = interpolate(since, [0, 10], [0, 1], {...clamp, easing: EASE});
  const devIn = useSpring(0, 200, 24);
  // Screen takes the crop's aspect; the block is pushed up just enough that the
  // two-line body and the step dots stay above the bottom safe line.
  const aspect = sectionAspect(section);
  const devH = Math.round(SCREEN_W / aspect) + 32;
  const devTop = Math.min(700, SAFE_BOTTOM - 80 - 190 - devH);
  return (
    <SceneFade duration={total} fadeIn={10} fadeOut={10}>
      {cur && (
        <div
          style={{
            ...fontBase,
            position: 'absolute',
            left: M,
            right: M,
            top: devTop - 200,
            opacity: capIn,
            transform: `translateY(${(1 - capIn) * 14}px)`,
          }}
        >
          <div style={{display: 'flex', alignItems: 'center', gap: 24}}>
            <div
              style={{
                width: 84,
                height: 84,
                borderRadius: 99,
                background: C.go,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 44,
                fontWeight: 800,
              }}
            >
              {idx + 1}
            </div>
            <div style={{fontSize: 88, fontWeight: 800, letterSpacing: '-0.04em', whiteSpace: 'nowrap'}}>
              {cur.title}
            </div>
          </div>
        </div>
      )}
      <div style={{position: 'absolute', left: M, top: devTop - 62}}>
        <FootageBadge section={section} size={26} />
      </div>
      <div
        style={{
          position: 'absolute',
          left: (1080 - DEV_W) / 2,
          top: devTop,
          opacity: devIn,
          transform: `scale(${0.96 + devIn * 0.04})`,
        }}
      >
        <DeviceFrame screenWidth={SCREEN_W} aspect={aspect}>
          <Footage section={section} screenWidth={SCREEN_W} aspect={aspect} />
        </DeviceFrame>
      </div>
      {cur && (
        <div
          style={{
            ...fontBase,
            position: 'absolute',
            left: M,
            right: M,
            top: devTop + devH + 40,
            fontSize: 54,
            fontWeight: 600,
            lineHeight: 1.32,
            letterSpacing: '-0.03em',
            color: C.text,
            opacity: capIn,
            transform: `translateY(${(1 - capIn) * 10}px)`,
          }}
        >
          {cur.body}
        </div>
      )}
      {/* step dots */}
      <div style={{position: 'absolute', left: M, top: SAFE_BOTTOM - 40, display: 'flex', gap: 14}}>
        {anns.map((_, i) => (
          <div
            key={i}
            style={{
              width: i === idx ? 56 : 18,
              height: 18,
              borderRadius: 99,
              background: i === idx ? C.goLight : i < idx ? 'rgba(91,155,234,0.45)' : C.lineStrong,
            }}
          />
        ))}
      </div>
    </SceneFade>
  );
};

export const R_OUTRO = 240;
export const HASHTAGS = ['#HMG차량용앱개발공모전', '#스테이고', '#전기차충전'];

export const ReelOutro: React.FC = () => (
  <SceneFade duration={R_OUTRO} fadeOut={20}>
    <AbsoluteFill style={{...fontBase, padding: `0 ${M}px`, justifyContent: 'center'}}>
      <div style={{marginTop: -160}}>
        <WipeLine delay={4} style={{fontSize: 80, fontWeight: 800, letterSpacing: '-0.04em', lineHeight: 1.22}}>
          떠날 때를
        </WipeLine>
        <WipeLine delay={12} style={{fontSize: 80, fontWeight: 800, letterSpacing: '-0.04em', lineHeight: 1.22}}>
          차가 먼저 알려준다
        </WipeLine>
        <div style={{marginTop: 80}}>
          <Wordmark size={96} delay={34} />
        </div>
        <Rise delay={46} style={{marginTop: 28}}>
          <div style={{fontSize: 36, fontWeight: 500, color: C.sub}}>Pleos Connect 차량용 앱</div>
        </Rise>
        <Rise delay={62} style={{marginTop: 80}}>
          <div style={{display: 'flex', flexWrap: 'wrap', gap: 16}}>
            {HASHTAGS.map((h) => (
              <div
                key={h}
                style={{
                  fontSize: 38,
                  fontWeight: 700,
                  color: h === HASHTAGS[0] ? C.text : C.sub,
                  padding: '14px 26px',
                  borderRadius: 999,
                  border: `1px solid ${h === HASHTAGS[0] ? 'rgba(91,155,234,0.7)' : C.lineStrong}`,
                  background: h === HASHTAGS[0] ? 'rgba(31,111,209,0.22)' : 'transparent',
                }}
              >
                {h}
              </div>
            ))}
          </div>
        </Rise>
      </div>
    </AbsoluteFill>
    <div style={{...fontBase, position: 'absolute', left: M, right: M, top: SAFE_BOTTOM - 110}}>
      <Rise delay={76} y={8}>
        <div style={{fontSize: 28, fontWeight: 500, color: C.muted, lineHeight: 1.45}}>
          {/* break after the first clause so the line never orphans a word */}
          <div>{DISCLOSURE.slice(0, DISCLOSURE.indexOf(' · '))}</div>
          <div>{DISCLOSURE.slice(DISCLOSURE.indexOf(' · ') + 3)}</div>
        </div>
      </Rise>
    </div>
  </SceneFade>
);
