# [20] 모델 정의 (3분) — 노트북과 API가 같은 파일을 import (학습/서빙 불일치 방지)
import torch, torch.nn as nn

class FireLSTM(nn.Module):
    """입력 60일 피처 → 다음 7일 각각 P(탐지). 기후값 로짓에 대한 잔차를 학습."""
    def __init__(self, n_feat, n_static=2, horizon=7, hidden=64, layers=2, dropout=0.2):
        super().__init__()
        self.lstm = nn.LSTM(n_feat, hidden, num_layers=layers, batch_first=True, dropout=dropout)
        self.head = nn.Sequential(nn.Linear(hidden + n_static + horizon, 64), nn.ReLU(), nn.Linear(64, horizon))
        nn.init.zeros_(self.head[-1].weight); nn.init.zeros_(self.head[-1].bias)   # 시작점 = 기후값

    def forward(self, x, static, clim_future):
        h = self.lstm(x)[0][:, -1]
        lc = torch.logit(clim_future.clamp(1e-4, 1 - 1e-4))
        return self.head(torch.cat([h, static, lc], 1)) + lc      # logits
