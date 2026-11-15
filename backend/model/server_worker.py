"""Private stdio worker: bounded local inference, no listening socket or control code."""
import json
import sys
from predict_optimized import OptimizedPredictor

def send(value):
    print(json.dumps(value, ensure_ascii=False, allow_nan=False), flush=True)

def main():
    sys.stdin.reconfigure(encoding='utf-8')
    sys.stdout.reconfigure(encoding='utf-8')
    predictor = OptimizedPredictor()
    send({'ready':True, 'model':'fusion_50', 'execution_allowed':False})
    while True:
        line = sys.stdin.readline(240001)
        if not line:break
        if len(line)>240000:
            raise ValueError('Worker input too large')
        request = json.loads(line)
        try:
            if request.get('action')=='matrix':
                result=predictor.predict_matrix(request['temperatures_c'], 'live_cached_thermal_frame')
            else:raise ValueError('Unsupported action')
            send({'id':request.get('id'), 'ok':True, 'result':result})
        except (KeyError, TypeError, ValueError) as error:
            send({'id':request.get('id'), 'ok':False, 'error':str(error)})

if __name__=='__main__':main()
