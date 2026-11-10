"""Solutia de referinta pentru QLR: gaseste L <= 1000 in <= 100 de operatii.

Idee: singurul lucru testabil CU CERTITUDINE e "L divide N?" (proba de multiplu):
  - citesc la pozitia absoluta N; daca bitul difera de bitul cunoscut al nodului 0,
    atunci sigur NU am aterizat pe nodul 0, deci L nu divide N (2 operatii);
  - daca bitul coincide, poate fi coincidenta: ma intorc la 0, inversez bitul
    (toggle), sar iar la N si recitesc — doar nodul 0 s-a putut schimba, deci
    raspunsul e cert in ambele sensuri (inca 4 operatii, total 6).

Cautare: tin multimea candidatilor C = {1..1000}; la fiecare pas aleg
N = lcm(S) pentru un subset S ales lacom, astfel incat divizorii lui N din C
sa fie cam jumatate din C. Raspunsul cert al probei injumatateste C.
~10-11 teste x max 6 operatii + citirea initiala => sub bugetul E=100.
"""

from math import gcd

MAX_L = 1000


def _lcm(a, b):
    return a * b // gcd(a, b)


def choose_test(cand):
    """Alege N astfel incat |{d in cand : d divide N}| sa fie ~ len(cand)/2.

    Parcurge candidatii descrescator si ii adauga lacom in lcm cat timp
    numarul de candidati care ar raspunde "da" nu depaseste jumatatea.
    """
    target = max(1, len(cand) // 2)
    N = 1
    for d in sorted(cand, reverse=True):
        if N % d == 0:
            continue                      # d e deja in partea "da"
        N2 = _lcm(N, d)
        yes2 = sum(1 for e in cand if N2 % e == 0)
        if yes2 <= target:
            N = N2
    if N == 1:
        N = min(cand)                     # fallback: separa minimul de rest
    return N


_test_cache = {}


def choose_test_cached(cand):
    key = tuple(cand)
    if key not in _test_cache:
        _test_cache[key] = choose_test(cand)
    return _test_cache[key]


def solve(q, max_L=MAX_L, stats=None):
    """Intoarce lungimea L a buclei folosind doar q.move/q.read/q.write."""
    pos = 0                # pozitia absoluta a pointerului (suma mutarilor)
    c = q.read()           # bitul curent al nodului 0 — 1 operatie
    cand = list(range(1, max_L + 1))
    tests = 0
    while len(cand) > 1:
        N = choose_test_cached(cand)
        tests += 1
        # proba de multiplu: "L divide N?"
        q.move(N - pos); pos = N          # 1 op — sar direct la pozitia absoluta N
        v = q.read()                      # 1 op
        if v != c:
            ans = False                   # cert: nu sunt pe nodul 0
        else:
            q.move(-pos); pos = 0         # 1 op — inapoi la nodul 0
            c ^= 1
            q.write(c)                    # 1 op — toggle: nodul 0 isi schimba bitul
            q.move(N); pos = N            # 1 op — sar iar la N
            ans = (q.read() == c)         # 1 op — doar nodul 0 se putea schimba
        cand = [d for d in cand if (N % d == 0) == ans]
    if stats is not None:
        stats["tests"] = tests
    return cand[0]
