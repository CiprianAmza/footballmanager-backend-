"""F5 — validarea empirica: solutia gaseste L corect si sub buget pentru
TOATE L din 1..1000, pe benzi aleatoare si pe benzile extreme (toti 0 / toti 1).
"""

from qlr_sim import QLR
from solve import solve, MAX_L
from solve_naive import solve_naive


def run():
    max_energy = 0
    max_tests = 0
    worst = None
    for L in range(1, MAX_L + 1):
        tapes = [
            ("random", None),
            ("zeros", [0] * L),
            ("ones", [1] * L),
        ]
        for name, bits in tapes:
            q = QLR(L, bits=bits, E=100, seed=L)
            stats = {}
            got = solve(q, stats=stats)
            assert got == L, f"L={L} tape={name}: got {got}"
            if q.energy > max_energy:
                max_energy = q.energy
                worst = (L, name)
            max_tests = max(max_tests, stats["tests"])
    print(f"toate cele {MAX_L} lungimi x 3 benzi: corect")
    print(f"energie maxima: {max_energy} (L={worst[0]}, banda={worst[1]})")
    print(f"numar maxim de teste: {max_tests}")
    print(f"limita garantata: 1 + 6*{max_tests} = {1 + 6 * max_tests} operatii")

    # contrast F1: metoda naiva pe L=1000
    q = QLR(1000, E=10**9, seed=7)
    got = solve_naive(q)
    assert got == 1000
    print(f"metoda naiva la L=1000: corecta, dar {q.energy} operatii (buget: 100)")


if __name__ == "__main__":
    run()
