"""Event-triggered review with periodic false-negative audits and bounded retries."""
from collections import Counter, deque
import math


class ReviewScheduler:
    def __init__(self):
        self.calls=deque()
        self.total=0
        self.last_call=-math.inf
        self.last_event=None
        self.reasons=Counter()
        self.backoff_until=-math.inf

    def reason(self, snapshot, now, *, interval=300, action_needed=False, permission_valid=False):
        if not snapshot['ready'] or now < self.backoff_until or now-self.last_call < 20:
            return None
        if action_needed and not permission_valid and now-self.last_call>=60:
            return 'control_revalidation'
        if snapshot['event_id']!=self.last_event:
            return 'initial_or_changed_state'
        if snapshot['state']=='uncertain' and now-self.last_call>=60:
            return 'persistent_ambiguity'
        if now-self.last_call>=interval:
            return 'periodic_audit'
        return None

    def reserve(self, snapshot, reason, now, max_hour, max_session):
        while self.calls and now-self.calls[0]>=3600:
            self.calls.popleft()
        if len(self.calls)>=max_hour or self.total>=max_session:
            return False
        self.calls.append(now)
        self.total+=1
        self.last_call=now
        self.last_event=snapshot['event_id']
        self.reasons[reason]+=1
        return True

    def failed(self, now):
        self.backoff_until=now+60

    def status(self):
        return {'total_calls':self.total,'trigger_counts':dict(self.reasons),
                'minimum_interval_seconds':20,'same_event_retry_seconds':60}
