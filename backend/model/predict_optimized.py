"""Local fused thermal inference with explicit abstention."""
import hashlib
import json
from pathlib import Path

import joblib
import numpy as np
import onnxruntime as ort

from features_v2 import thermal_features
from preprocess import LABELS, CN, extract_features, crop_letterbox

PROJECT = Path(__file__).resolve().parent
def read(path): return json.loads(path.read_text(encoding="utf-8"))
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def decision_details(primary, secondary, config):
    p = np.asarray(primary, dtype=float)
    q = np.asarray(secondary, dtype=float)
    for scores in (p, q):
        if (scores.shape != (4,) or not np.isfinite(scores).all()
                or (scores < 0).any() or (scores > 1).any()
                or not np.isclose(scores.sum(), 1, atol=1e-5)):
            raise ValueError('Expected four finite normalized nonnegative scores')
    i, j = int(p.argmax()), int(q.argmax())
    ordered = np.sort(p)
    margin = float(ordered[-1] - ordered[-2])
    reasons = []
    if p[i] < config['min_top_score']:
        reasons.append('low_top_score')
    if margin < config['min_margin']:
        reasons.append('small_class_margin')
    if config['require_top_two_agreement'] and i != j:
        reasons.append('model_disagreement')
    return {
        'predicted_state': 'uncertain' if reasons else LABELS[i],
        'predicted_state_cn': '不确定，需要复核' if reasons else CN[i],
        'raw_predicted_state': LABELS[i],
        'secondary_predicted_state': LABELS[j],
        'abstention_reasons': reasons,
        'top_score': float(p[i]), 'class_margin': margin,
        'scores': dict(zip(LABELS, map(float, p))),
        'secondary_scores': dict(zip(LABELS, map(float, q))),
        'scores_are_calibrated_probabilities': False,
        # An accepted class is still research output, never actuator authorization.
        'research_only': True, 'review_required': True,
        'recognition_release_ready': False,
        'execution_allowed': False, 'air_conditioning_command': None,
    }


class OptimizedPredictor:
    """Load models once for repeated offline frames; no device or network operations."""
    def __init__(self, run=PROJECT / 'runs' / 'bedside_v003', base=PROJECT / 'runs' / 'bedside_v001'):
        self.run, self.base = Path(run), Path(base)
        bundle = read(self.run / 'bundle_manifest.json')
        for entry in bundle['files']:
            root = self.base if entry['root'] == 'base' else self.run
            if sha(root / entry['path']) != entry['sha256']:
                raise ValueError(f'Model bundle changed: {entry["path"]}')
        self.roi = read(self.base / "calibration.json")["roi"]
        self.selection = read(self.run / "selection_locked.json")
        self.background = np.load(self.base / 'background_c.npy', allow_pickle=False)
        self.guard = self.selection['guard']
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        self.network = ort.InferenceSession(str(self.run / 'models' / 'fusion_fp32.onnx'),
            sess_options=options, providers=['CPUExecutionProvider'])
        self.secondary = joblib.load(self.base / 'models' / 'random_forest.joblib')
        self.secondary.n_jobs = 1

    def predict_matrix(self, matrix, source_frame='memory'):
        matrix = np.asarray(matrix, dtype=np.float32)
        if matrix.shape != (62, 80) or not np.isfinite(matrix).all():
            raise ValueError('Expected finite 62x80 Celsius matrix')
        x = np.stack([crop_letterbox(matrix, self.roi),
            crop_letterbox(matrix-self.background, self.roi)])[None].astype(np.float32)
        return self._predict_tensor(x, source_frame)

    def _predict_tensor(self, x, source_frame):
        p = self.network.run(None, {'thermal': x, 'features': thermal_features(x)})[0][0]
        q = self.secondary.predict_proba(extract_features(x))[0]
        result = decision_details(p, q, self.guard)
        result.update(model=self.selection['chosen_model'], secondary_model='v1_random_forest',
            source_frame=source_frame, guard=dict(self.guard),
            scope='experimental_bedside_static_state_offline',
            requires_same_camera_pose_and_background_calibration=True,
)
        return result

