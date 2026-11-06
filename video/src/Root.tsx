import React from 'react';
import {AbsoluteFill, Composition, Series, type CalculateMetadataFunction} from 'remotion';
import './theme';
import {FPS} from './theme';
import {findMusic, loadTimeline, reelSection, sectionFrames, type Timeline} from './timeline';
import {Backdrop} from './components/motion';
import {Music} from './components/music';
import {HOOK, Hook, HOW, HowItWorks, LiveDemo, OUTRO, Outro, Problem, PROBLEM, Solution, SOLUTION} from './demo/scenes';
import {R_HOOK, R_OUTRO, R_PROBLEM, ReelDemo, ReelHook, ReelOutro, ReelProblem} from './reel/scenes';

type Props = {
  /** Play public/audio/bgm-*.{mp3,wav,m4a} if present. */
  music: boolean;
  musicVolume: number;
  /** Filled by calculateMetadata from public/demo/timeline.json. */
  timeline?: Timeline;
  musicSrc?: string | null;
};

const StayGoDemo: React.FC<Props> = ({timeline, musicSrc, musicVolume}) => {
  if (!timeline) return null;
  return (
    <AbsoluteFill>
      <Backdrop />
      <Series>
        <Series.Sequence durationInFrames={HOOK}><Hook /></Series.Sequence>
        <Series.Sequence durationInFrames={PROBLEM}><Problem /></Series.Sequence>
        <Series.Sequence durationInFrames={SOLUTION}><Solution /></Series.Sequence>
        <Series.Sequence durationInFrames={sectionFrames(timeline)}><LiveDemo section={timeline} /></Series.Sequence>
        <Series.Sequence durationInFrames={HOW}><HowItWorks /></Series.Sequence>
        <Series.Sequence durationInFrames={OUTRO}><Outro /></Series.Sequence>
      </Series>
      <Music src={musicSrc ?? null} volume={musicVolume} />
    </AbsoluteFill>
  );
};

const StayGoReel: React.FC<Props> = ({timeline, musicSrc, musicVolume}) => {
  if (!timeline) return null;
  const reel = reelSection(timeline);
  return (
    <AbsoluteFill>
      <Backdrop vertical />
      <Series>
        <Series.Sequence durationInFrames={R_HOOK}><ReelHook /></Series.Sequence>
        <Series.Sequence durationInFrames={R_PROBLEM}><ReelProblem /></Series.Sequence>
        <Series.Sequence durationInFrames={sectionFrames(reel)}><ReelDemo section={reel} /></Series.Sequence>
        <Series.Sequence durationInFrames={R_OUTRO}><ReelOutro /></Series.Sequence>
      </Series>
      <Music src={musicSrc ?? null} volume={musicVolume} />
    </AbsoluteFill>
  );
};

// Music is mastered to exactly 150 s / 45 s, so the footage sections have fixed lengths.
const DEMO_SECTION = 4500 - (HOOK + PROBLEM + SOLUTION + HOW + OUTRO); // 2400 f = 80 s
const REEL_SECTION = 1350 - (R_HOOK + R_PROBLEM + R_OUTRO); // 780 f = 26 s

const assertLength = (what: string, got: number, want: number) => {
  if (got !== want) {
    throw new Error(
      `timeline.json: ${what} clips last ${(got / FPS).toFixed(2)} s (${got} f) but must total exactly ${want / FPS} s (${want} f) to stay in sync with the music. Adjust endAt/playbackRate.`,
    );
  }
};

const demoMeta: CalculateMetadataFunction<Props> = async ({props}) => {
  const timeline = await loadTimeline();
  assertLength('main', sectionFrames(timeline), DEMO_SECTION);
  return {
    durationInFrames: 4500,
    props: {...props, timeline, musicSrc: props.music ? findMusic('bgm-demo') : null},
  };
};

const reelMeta: CalculateMetadataFunction<Props> = async ({props}) => {
  const timeline = await loadTimeline();
  assertLength('reel', sectionFrames(reelSection(timeline)), REEL_SECTION);
  return {
    durationInFrames: 1350,
    props: {...props, timeline, musicSrc: props.music ? findMusic('bgm-reel') : null},
  };
};

export const RemotionRoot: React.FC = () => (
  <>
    <Composition
      id="StayGoDemo"
      component={StayGoDemo}
      width={1920}
      height={1080}
      fps={FPS}
      durationInFrames={4500}
      defaultProps={{music: true, musicVolume: 1} as Props}
      calculateMetadata={demoMeta}
    />
    <Composition
      id="StayGoReel"
      component={StayGoReel}
      width={1080}
      height={1920}
      fps={FPS}
      durationInFrames={1350}
      defaultProps={{music: true, musicVolume: 1} as Props}
      calculateMetadata={reelMeta}
    />
  </>
);
