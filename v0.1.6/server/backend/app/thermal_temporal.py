"""Causal, timestamp-weighted evidence; scores never become calibrated accuracy."""
from collections import deque
import copy
import math
import uuid


def category(state):
    if state in ('partially_uncovered', 'largely_uncovered', 'uncovered', 'confirmed_uncovered', 'uncovered_candidate'):
        return 'uncovered'
    return state if state in ('covered', 'empty') else 'uncertain'


class TemporalEvidence:
    def __init__(self, window=30, history=120, max_gap=6):
        self.window, self.history_seconds, self.max_gap = window, history, max_gap
        self.frames = deque(maxlen=180)
        self.revision = 0
        self.reset()

    def reset(self):
        self.frames.clear()
        self.revision += 1
        self.event_id = uuid.uuid4().hex
        self.source = None
        self.run_state = None
        self.run_start = None
        self.last_stable = None

    def observe(self, evidence):
        r = evidence['result']
        t = r['frame_received_epoch']
        if type(t) not in (int, float) or not math.isfinite(t):
            raise ValueError('invalid frame time')
        identity = tuple(r['source_identity'])
        if self.source is not None and identity != self.source:
            self.reset()
        self.source = identity
        if self.frames:
            old = self.frames[-1]['result']
            if r['frame_id'] == old['frame_id']:
                return False
            if t <= old['frame_received_epoch']:
                self.reset()
                raise ValueError('out-of-order frame')
            if t-old['frame_received_epoch'] > self.max_gap:
                self.reset()
                self.source = identity
        state = category(r['predicted_state'])
        if state != self.run_state:
            self.revision += 1
            self.run_state, self.run_start = state, t
        self.frames.append(copy.deepcopy(evidence))
        while self.frames and t-self.frames[0]['result']['frame_received_epoch'] > self.history_seconds:
            self.frames.popleft()
        return True

    def snapshot(self, now, confirmation=20):
        if not self.frames:
            return {'state':'uncertain', 'ready':False, 'event_id':self.event_id,
                    'revision':self.revision, 'support':0., 'duration_seconds':0., 'uncertain_seconds':0.}
        end = self.frames[-1]['result']['frame_received_epoch']
        start = max(end-max(self.window, confirmation), self.frames[0]['result']['frame_received_epoch'])
        weights = {s:0. for s in ('covered','uncovered','empty','uncertain')}
        seq = list(self.frames)
        for a,b in zip(seq,seq[1:]):
            x,y = a['result']['frame_received_epoch'], b['result']['frame_received_epoch']
            duration = max(0., min(y,end)-max(x,start))
            if y-x <= self.max_gap:
                weights[category(a['result']['predicted_state'])] += duration
        span = end-start
        winner = max(weights,key=weights.get)
        support = weights[winner]/span if span else 0.
        fresh = 0 <= now-end <= self.max_gap
        ready = fresh and span >= confirmation
        stable = ready and support >= .8 and winner == self.run_state and winner != 'uncertain'
        state = winner if stable else 'uncertain'
        if stable and state != self.last_stable:
            self.event_id = uuid.uuid4().hex
            self.last_stable = state
        return {'state':state, 'ready':ready, 'event_id':self.event_id, 'revision':self.revision,
                'support':round(support,4), 'support_is_accuracy':False,
                'duration_seconds':round(end-self.run_start,2),
                'uncertain_seconds':round(end-self.run_start,2) if self.run_state=='uncertain' else 0.,
                'observed_span_seconds':round(span,2), 'fresh':fresh,
                'latest_state':self.run_state, 'frame_count':len(seq), 'weights_seconds':weights}

    def keyframes(self, confirmation=20):
        """Chronological baseline, confirmation start, middle, latest; no invented baseline."""
        seq = list(self.frames)
        if len(seq)<2:
            return []
        end = seq[-1]['result']['frame_received_epoch']
        indices = {0, len(seq)-1}
        for target in (end-confirmation, end-confirmation/2):
            indices.add(min(range(len(seq)),key=lambda i:abs(seq[i]['result']['frame_received_epoch']-target)))
        return [copy.deepcopy(seq[i]) for i in sorted(indices)]

    def supports(self, state, confirmation):
        """Unknown intervals never count as positive local confirmation."""
        if not self.frames:
            return False
        end=self.frames[-1]['result']['frame_received_epoch']
        return self.run_state==state and end-self.run_start>=confirmation
