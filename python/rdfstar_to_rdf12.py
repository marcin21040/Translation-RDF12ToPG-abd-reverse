#!/usr/bin/env python3
"""
Konwersja YAGO (annotated facts, składnia RDF-star <<s p o>> meta-p meta-o)
na RDF 1.2 N-Triples: reifikacja przez rdf:reifies + triple term <<( ... )>>.

Zob. https://www.w3.org/TR/rdf12-turtle/#triple-terms
oraz RDF 1.2 N-Triples (ten sam zapis termu trójkowego).
"""
import sys
import re
from pathlib import Path

RDF_REIFIES_IRI = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies>"


def parse_term(term):
    """Czyści termin z ewentualnych białych znaków."""
    return term.strip()


def process_yago_line(line):
    """
    Rozpoznaje dwa typy linii:
    1. <<s p o>> p2 o2 .  -> Meta-fakt (do reifikacji)
    2. s p o .            -> Zwykły fakt
    """
    line = line.strip()
    if not line or line.startswith('#'):
        return None

    star_match = re.match(r'^<<\s*(.+)\s*>>\s*(<[^>]+>)\s*(.+)\s*\.\s*$', line)
    if star_match:
        inner, p2, o2 = star_match.groups()
        inner_parts = re.findall(r'(<[^>]+>|"[^"]+"(?:\^\^<[^>]+>)?)', inner)
        if len(inner_parts) == 3:
            return {
                'type': 'star',
                'inner': [parse_term(t) for t in inner_parts],
                'p2': parse_term(p2),
                'o2': parse_term(o2)
            }

    normal_parts = re.findall(r'(<[^>]+>|"[^"]+"(?:\^\^<[^>]+>)?)', line)
    if len(normal_parts) >= 3:
        return {
            'type': 'normal',
            's': parse_term(normal_parts[0]),
            'p': parse_term(normal_parts[1]),
            'o': parse_term(normal_parts[2])
        }

    return None


def main():
    if len(sys.argv) < 3:
        print("Użycie: python rdfstar_to_rdf12.py <wejscie.ntx> <wyjscie.nt>")
        sys.exit(1)

    in_path = Path(sys.argv[1])
    out_path = Path(sys.argv[2])

    print(f"Przetwarzanie {in_path}...", file=sys.stderr)

    count = 0
    reified_triples = {}

    with open(in_path, 'r', encoding='utf-8') as f_in, \
            open(out_path, 'w', encoding='utf-8') as f_out:

        # RDF 1.2: wersja dokumentu (wymagana / zalecana przy triple terms)
        f_out.write('VERSION "1.2"\n')

        for line in f_in:
            data = process_yago_line(line)
            if not data:
                continue

            if data['type'] == 'star':
                s_in, p_in, o_in = data['inner']
                triple_key = f"{s_in} {p_in} {o_in}"

                if triple_key not in reified_triples:
                    bnode = f"_:b{hash(triple_key) & 0xffffffff}"
                    reified_triples[triple_key] = bnode
                    # RDF 1.2: jedna trójka zamiast rdf:Statement + subject/predicate/object
                    f_out.write(
                        f"{bnode} {RDF_REIFIES_IRI} <<( {s_in} {p_in} {o_in} )>> .\n"
                    )

                bnode = reified_triples[triple_key]
                f_out.write(f'{bnode} {data["p2"]} {data["o2"]} .\n')

            elif data['type'] == 'normal':
                f_out.write(f'{data["s"]} {data["p"]} {data["o"]} .\n')

            count += 1
            if count % 10000 == 0:
                print(f"Przetworzono {count} linii...", end='\r', file=sys.stderr)

    print(f"\nSukces! Wynik w formacie N-Triples zapisano w: {out_path}")


if __name__ == "__main__":
    main()
