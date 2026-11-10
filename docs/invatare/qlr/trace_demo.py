"""Urme de executie concrete pentru manual: valori reale, nu inventate."""

from qlr_sim import QLR
from solve import choose_test_cached, MAX_L, solve


def trace(L, max_L=MAX_L, bits=None, seed=None):
    q = QLR(L, bits=bits, E=1000, seed=seed if seed is not None else L)
    pos = 0
    c = q.read()
    cand = list(range(1, max_L + 1))
    rows = []
    while len(cand) > 1:
        N = choose_test_cached(cand)
        q.move(N - pos); pos = N
        v = q.read()
        if v != c:
            ans, cost = False, 2
        else:
            q.move(-pos); pos = 0
            c ^= 1
            q.write(c)
            q.move(N); pos = N
            ans = (q.read() == c)
            cost = 6
        cand = [d for d in cand if (N % d == 0) == ans]
        rows.append((N, ans, cost, len(cand)))
    return rows, q.energy, cand[0]


def show(L, max_L=MAX_L, bits=None, label=""):
    rows, energy, got = trace(L, max_L=max_L, bits=bits)
    print(f"--- L={L}, max_L={max_L} {label} -> gasit {got}, energie {energy}")
    for i, (N, ans, cost, ncand) in enumerate(rows, 1):
        n_str = str(N) if N < 10**12 else f"~10^{len(str(N)) - 1} ({str(N)[:8]}...)"
        print(f"  test {i}: N={n_str}  raspuns={'DA' if ans else 'NU'}  cost={cost}  raman {ncand} candidati")


if __name__ == "__main__":
    # jucaria didactica: bucla de 6 noduri, candidati doar 1..10
    show(6, max_L=10, bits=[1, 0, 0, 1, 1, 0], label="banda fixa [1,0,0,1,1,0]")
    # cazul real reprezentativ
    show(12)
    # cazul cu energia maxima din validare
    show(48)
    q0 = choose_test_cached(tuple(range(1, MAX_L + 1)))
    first = choose_test_cached(list(range(1, MAX_L + 1)))
    yes = sum(1 for e in range(1, MAX_L + 1) if first % e == 0)
    print(f"primul N pentru C={{1..1000}}: {len(str(first))} cifre, {yes} candidati din 1000 il divid")
