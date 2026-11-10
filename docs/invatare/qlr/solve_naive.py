"""F1 — metoda "trenului" din kata-ul prerechizit (How many Wagons Are in the Train?).

Marcheaza nodul de start cu 1, merge pas cu pas si verifica fiecare 1 gasit:
il sterge, se intoarce la start si se uita daca marcajul propriu a disparut.
Corecta pentru orice L, dar costul e O(L) — moare la bugetul E=100 al QLR.
"""


def solve_naive(q):
    q.write(1)                    # marcajul nostru in nodul de start
    steps = 0
    while True:
        q.move(1)
        steps += 1
        if q.read() == 1:
            # ori e marcajul nostru (am inchis cercul), ori un 1 preexistent
            q.write(0)            # il sterg ca sa pot verifica
            q.move(-steps)        # inapoi la start
            if q.read() == 0:     # marcajul meu a disparut => 1-ul sters era chiar el
                return steps
            q.move(steps)         # marcajul e intact => 1 preexistent; continui de unde eram
