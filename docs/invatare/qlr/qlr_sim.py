"""Simulator local al dispozitivului QLR (Quantum Loop Regulator).

Enuntul kata-ului e complet ("no hidden tests"), deci dispozitivul poate fi
reimplementat fidel: o bucla circulara de L noduri binare, un pointer, si un
contor de energie in care fiecare move/read/write costa exact 1.
"""

import random


class EnergyExceeded(Exception):
    """Bugetul E a fost depasit — echivalentul esecului din kata."""


class QLR:
    def __init__(self, L, bits=None, E=100, seed=None):
        if L < 1:
            raise ValueError("L trebuie sa fie >= 1")
        rng = random.Random(seed)
        self.L = L
        self.tape = list(bits) if bits is not None else [rng.randint(0, 1) for _ in range(L)]
        if len(self.tape) != L:
            raise ValueError("bits trebuie sa aiba exact L elemente")
        self.pos = 0          # pointerul porneste la un nod arbitrar; il numim 0
        self.E = E            # bugetul total de energie
        self.energy = 0       # energia consumata pana acum

    def _spend(self):
        self.energy += 1
        if self.energy > self.E:
            raise EnergyExceeded(f"depasit E={self.E} (L={self.L})")

    def move(self, x):
        """Muta pointerul x noduri (x oricat de mare, pozitiv sau negativ). Cost 1."""
        self._spend()
        self.pos = (self.pos + x) % self.L

    def read(self):
        """Citeste bitul nodului curent. Cost 1."""
        self._spend()
        return self.tape[self.pos]

    def write(self, v):
        """Scrie bitul v in nodul curent. Cost 1."""
        self._spend()
        self.tape[self.pos] = v
