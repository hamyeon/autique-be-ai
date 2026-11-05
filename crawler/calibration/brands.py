# 매물 제목에서 브랜드를 찾는다 - 브랜드 단위 시세(used_market_prices_by_brand.csv)용.
#
# 모델을 특정 못 한 매물("컨버스 하이 블랙 250", "나이키 운동화")이 크롤링 원본의 대부분이다.
# 모델 시세는 못 내지만 브랜드 시세의 표본은 된다. 백엔드는 모델 시세도 KREAM도 없을 때
# 마지막 폴백으로 이 값을 쓴다(정확도가 낮다는 걸 추천 사유에 밝힌다).
#
# 브랜드 이름은 시세 CSV와 백엔드 BrandAliases의 표준 표기를 따른다.
#
# 짧은 한글 별칭은 앞뒤가 한글이 아닐 때만 인정한다 - "어그"가 "어그로"에, "킨"이 "스킨·치킨"에,
# "에코"가 "에코백"에 붙지 않게 한다. "캠퍼"는 "캠퍼스"(아디다스)를 빼야 한다.
import re

_K = r"(?<![가-힣])"   # 앞이 한글이 아니어야 한다
_KE = r"(?![가-힣])"   # 뒤가 한글이 아니어야 한다

BRANDS = [
    ("Nike", [r"나이키", r"nike", r"조던", r"jordan"]),
    ("Adidas", [r"아디다스", r"adidas"]),
    ("New Balance", [r"뉴발", r"new\s*balance"]),
    ("Converse", [r"컨버스", r"converse"]),
    ("Vans", [_K + r"반스", r"\bvans\b"]),
    ("Dr. Martens", [r"닥터마틴", r"닥마", r"dr\.?\s*martens"]),
    ("Asics", [r"아식스", r"asics"]),
    ("Puma", [r"푸마", r"\bpuma\b"]),
    ("Fila", [r"휠라", r"\bfila\b"]),
    ("Reebok", [r"리복", r"reebok"]),
    ("Salomon", [r"살로몬", r"salomon"]),
    ("Hoka", [r"호카", r"\bhoka\b"]),
    ("UGG", [_K + r"어그" + r"(?!로)", r"\bugg\b"]),
    ("Crocs", [r"크록스", r"crocs"]),
    ("Skechers", [r"스케쳐스", r"스케처스", r"skechers"]),
    ("Onitsuka Tiger", [r"오니츠카", r"onitsuka"]),
    ("Birkenstock", [r"버켄", r"birkenstock"]),
    ("FitFlop", [r"핏플랍", r"fitflop"]),
    ("Rockport", [r"락포트", r"rockport"]),
    ("Rockfish", [r"락피쉬", r"락피시", r"rockfish"]),
    ("Hunter", [_K + r"헌터" + _KE, r"\bhunter\b"]),
    ("Keen", [_K + r"킨" + _KE, r"\bkeen\b"]),
    ("Camper", [r"캠퍼(?!스)", r"\bcamper\b"]),
    ("Ecco", [_K + r"에코" + _KE, r"\becco\b"]),
    ("Mizuno", [r"미즈노", r"mizuno"]),
    ("Golden Goose", [r"골든구스", r"golden\s*goose"]),
    ("Gucci", [r"구찌", r"gucci"]),
    ("Prada", [r"프라다", r"prada"]),
    ("Balenciaga", [r"발렌시아가", r"balenciaga"]),
    ("Miu Miu", [r"미우미우", r"miu\s*miu"]),
    ("Timberland", [r"팀버랜드", r"timberland"]),
    ("Clarks", [r"클락스", r"clarks"]),
    ("Merrell", [r"머렐", r"merrell"]),
    ("Teva", [_K + r"테바" + _KE, r"\bteva\b"]),
    ("The North Face", [r"노스페이스", r"north\s*face"]),
    ("Discovery", [r"디스커버리", r"discovery"]),
    ("Under Armour", [r"언더아머", r"under\s*armour"]),
    ("Prospecs", [r"프로스펙스", r"prospecs"]),
    ("On", [r"온러닝", r"on\s*running"]),
    ("Toms", [_K + r"탐스" + _KE, r"\btoms\b"]),
    ("Red Wing", [r"레드윙", r"red\s*wing"]),
    ("Paraboot", [r"파라부트", r"paraboot"]),
]

_COMPILED = [(brand, re.compile("|".join(patterns), re.IGNORECASE)) for brand, patterns in BRANDS]


def find_brand(title):
    """제목에 나온 브랜드. 없거나 두 개 이상이면 None - 여러 브랜드가 섞인 제목은 어느 쪽 시세도 아니다."""
    t = title or ""
    found = {brand for brand, pattern in _COMPILED if pattern.search(t)}
    if len(found) != 1:
        return None
    return found.pop()
