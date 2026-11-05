# 신발 모델 표 - 매물 제목에서 (브랜드, 모델키)를 찾는다.
#
# build_used_market_prices.py에 있던 것을 공용 모듈로 뺐다(#104). 구성품 계수도
# 같은 모델끼리 묶어야 해서 같은 표가 필요한데, 산출 스크립트가 다른 산출 스크립트를
# import하는 구조가 되면 어느 쪽이 표의 주인인지 알 수 없다. 표의 주인은 이 모듈이다
# (단, build_color_premiums.py는 아직 build_used_market_prices에서 find_model을 import한다).
#
# model_aliases.py와는 다른 표다. 그쪽은 KREAM 참조의 정확한 모델명(airforce1low)에
# 묶여 있어 참조가 없는 모델을 담을 수 없다. 이쪽은 참조와 무관한 자체 키를 쓴다.
# (브랜드, 모델키, 표시명, 별칭들, 브랜드 힌트)
#
# 브랜드 힌트: 별칭이 숫자뿐인 모델(530, 574, 1461 ...)은 제목에 브랜드 단서가 같이
# 있어야 매칭한다. "530"만으로 잡으면 무관한 숫자를 끌어들인다.
#
# "이지"는 일부러 없다. 슬라이드·폼러너·350이 한 단어에 뒤섞여(실측: 482건, 범위
# 1.8만~7.7만) 어느 모델의 시세도 아니게 된다. "이지 350"처럼 특정되는 것만 둔다.
MODELS = [
    # 골든구스는 맨 앞에 둔다. "골든구스 슈퍼스타"에서 "골든구스"와 "슈퍼스타"는 길이가 같아
    # (4자) 표 순서로 승부가 나는데, 뒤에 있으면 골든구스가 아디다스 슈퍼스타 시세에 섞인다
    # (실측: 슈퍼스타 192건 중 46건이 골든구스였고, 빼자 중앙값이 4.5만 -> 3.5만으로 내려갔다). 같은 길이 별칭끼리는 MODELS에서 앞선 쪽이 이긴다(FLAT 정렬이 안정 정렬).
    ("Golden Goose", "goldengoose", "Superstar (Golden Goose)", ["골든구스", "golden goose", "ggdb"], None),
    ("Nike", "airforce1", "Air Force 1", ["에어포스", "air force 1", "af1"], None),
    ("Nike", "dunklow", "Dunk Low", ["덩크 로우", "덩크로우", "덩크로", "dunk low"], None),
    ("Nike", "dunkhigh", "Dunk High", ["덩크 하이", "덩크하이", "dunk high"], None),
    ("Nike", "airmax90", "Air Max 90", ["에어맥스90", "에어맥스 90", "air max 90"], None),
    ("Nike", "airmax95", "Air Max 95", ["에어맥스95", "에어맥스 95", "air max 95"], None),
    ("Nike", "airmax97", "Air Max 97", ["에어맥스97", "에어맥스 97", "air max 97"], None),
    ("Nike", "jordan1", "Jordan 1", ["조던1", "조던 1", "에어조던1", "jordan 1"], None),
    ("Nike", "cortez", "Cortez", ["코르테즈", "cortez"], None),
    ("Nike", "blazer", "Blazer", ["블레이저", "blazer"], None),
    ("Nike", "vomero", "Vomero", ["보메로", "vomero"], None),
    ("Nike", "pegasus", "Pegasus", ["페가수스", "pegasus"], None),
    ("Nike", "v2krun", "V2K Run", ["v2k"], None),
    ("Adidas", "sambaog", "Samba", ["삼바", "samba"], None),
    ("Adidas", "gazelle", "Gazelle", ["가젤", "gazelle"], None),
    ("Adidas", "superstar", "Superstar", ["슈퍼스타", "superstar"], None),
    ("Adidas", "stansmith", "Stan Smith", ["스탠스미스", "스탠 스미스", "stan smith"], None),
    ("Adidas", "spezial", "Handball Spezial", ["스페지알", "spezial"], None),
    ("Adidas", "yeezy350", "Yeezy Boost 350", ["이지 350", "이지350", "yeezy 350"], None),
    ("New Balance", "nb327", "327", ["327"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb530", "530", ["530"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb574", "574", ["574"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb990", "990", ["990"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb993", "993", ["993"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb1906", "1906", ["1906"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb2002r", "2002R", ["2002r", "2002"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb9060", "9060", ["9060"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb550", "550", ["550"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("Dr. Martens", "dm1460", "1460", ["1460"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm1461", "1461", ["1461"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Asics", "gelkayano14", "Gel-Kayano 14", ["카야노14", "카야노 14", "kayano 14"], None),
    ("Asics", "gel1130", "Gel-1130", ["젤1130", "젤 1130", "gel-1130", "gel 1130"], None),
    ("Salomon", "xt6", "XT-6", ["xt-6", "xt6", "xt 6"], None),
    ("Converse", "chuck70", "Chuck 70", ["척70", "척 70", "chuck 70"], None),
    ("Crocs", "classicclog", "Classic Clog", ["클래식 클로그", "classic clog"], None),
    # --- #89 확대: 미매칭 매물 빈도 분석으로 추가 (scratchpad/coverage_candidates) ---
    # 기준: 표본 10건 이상 + 가격대 응집. "에어맥스"(번호 없음, 130건)와
    # "핏플랍"(브랜드 단위, 137건)은 여러 모델이 한 통에 섞여 제외 - "이지"와 같은 이유.
    ("Nike", "jordan4", "Jordan 4", ["조던4", "조던 4", "jordan 4"], None),
    ("Nike", "airmaxkoko", "Air Max Koko", ["에어맥스 코코", "맥스코코", "맥스 코코", "air max koko"], None),
    ("Dr. Martens", "dmadrian", "Adrian", ["아드리안", "adrian"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm2976", "2976 Chelsea", ["2976", "첼시"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("UGG", "tasman", "Tasman", ["타스만", "태즈먼", "tasman"], None),
    ("Hunter", "hunteroriginal", "Original Rain Boot", ["헌터"], ["레인", "부츠", "장화"]),
    ("Rockfish", "rockfishrain", "Weatherwear Rain Boot", ["락피쉬"], ["레인", "부츠", "장화", "웨더웨어"]),
    ("Skechers", "gowalk", "Go Walk", ["고워크", "gowalk", "go walk"], None),
    # --- #89 2차 확대: 검색어 확대 재크롤링(+4,574건) 후 재분석으로 추가 ---
    ("Vans", "oldskool", "Old Skool", ["올드스쿨", "올드 스쿨", "old skool"], None),
    ("Vans", "authentic", "Authentic", ["어센틱", "authentic"], ["반스", "vans"]),
    ("Vans", "slipon", "Slip-On", ["슬립온", "슬립 온"], ["반스", "vans"]),
    ("Converse", "chucktaylor", "Chuck Taylor All Star", ["척테일러", "척 테일러", "chuck taylor"], None),
    ("Birkenstock", "boston", "Boston", ["보스턴"], ["버켄", "birkenstock"]),
    ("Nike", "jordan3", "Jordan 3", ["조던3", "조던 3", "jordan 3"], None),
    # --- #97 3차 확대: 크롤링 원본 재분석(제목 기준 20건+ 후보). 새 크롤링 없이 별칭만 추가 ---
    # SB 덩크는 일반 덩크와 가격대가 다른 별개 라인이라 분리한다. "sb 덩크 로우"가 "덩크 로우"보다
    # 길어 먼저 검사되므로 SB 매물이 dunklow로 새지 않는다.
    ("Nike", "dunksb", "SB Dunk", ["sb 덩크 로우", "sb덩크로우", "sb 덩크 하이", "sb덩크하이", "덩크 로우 sb", "덩크로우 sb", "sb 덩크", "sb덩크", "덩크 sb", "sb dunk", "dunk sb"], None),
    ("Nike", "jordan11", "Jordan 11", ["조던11", "조던 11", "jordan 11"], None),
    ("Nike", "airmax1", "Air Max 1", ["에어맥스1 ", "에어맥스 1 ", "에어맥스1(", "air max 1 ", "에어맥스 1'", "에어맥스1'"], None),
    ("Adidas", "campus", "Campus", ["캠퍼스", "campus"], ["아디다스", "adidas"]),
    ("Adidas", "forum", "Forum", ["포럼", "forum"], ["아디다스", "adidas"]),
    ("Adidas", "sl72", "SL 72", ["sl72", "sl 72"], None),
    ("Adidas", "ozweego", "Ozweego", ["오즈위고", "ozweego"], None),
    ("Converse", "onestar", "One Star", ["원스타", "one star"], ["컨버스", "converse"]),
    ("Converse", "runstar", "Run Star", ["런스타", "run star"], None),
    ("Vans", "sk8hi", "Sk8-Hi", ["sk8-hi", "sk8 hi", "sk8hi", "스케이트 하이", "스케이트하이"], None),
    ("Asics", "novablast", "Novablast", ["노바블라스트", "novablast"], None),
    ("Salomon", "xt4", "XT-4", ["xt-4", "xt4", "xt 4"], None),
    ("Hoka", "bondi", "Bondi", ["본디", "bondi"], None),
    ("Puma", "speedcat", "Speedcat", ["스피드캣", "speedcat"], None),
    ("Puma", "palermo", "Palermo", ["팔레르모", "palermo"], None),
    ("Puma", "pumasuede", "Suede", ["푸마 스웨이드", "puma suede"], None),
    ("Onitsuka Tiger", "mexico66", "Mexico 66", ["멕시코66", "멕시코 66", "mexico 66"], None),
    ("Birkenstock", "arizona", "Arizona", ["아리조나", "arizona"], ["버켄", "birkenstock"]),
    ("UGG", "uggultramini", "Ultra Mini", ["울트라 미니", "울트라미니", "ultra mini"], ["어그", "ugg"]),
    ("UGG", "uggclassicmini", "Classic Mini", ["클래식 미니", "클래식미니", "classic mini"], ["어그", "ugg"]),
    ("New Balance", "nb480", "480", ["480"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb860", "860", ["860"], ["뉴발", "뉴 발", "new balance", "nb"]),
    # --- 4차 확대: 크롤링 원본에서 미매칭 매물 2.6만 건을 브랜드별로 다시 빈도 분석 ---
    # 기준은 이전과 같다: 표본 10건 이상(MIN_LISTINGS) + 한 모델로 볼 수 있는 가격대.
    # "아디제로"·"이지부스트"·"에어맥스"(번호 없음)·"리액트"·"ACG"·"테렉스"처럼 여러 모델이
    # 한 단어에 섞이는 라인은 넣지 않았다.
    # 컨버스: "컨버스 올스타"가 가장 흔한 표기인데 별칭이 없어 척테일러 시세에서 빠지고 있었다.
    # "올스타"만으로는 다른 브랜드에도 붙을 수 있어 브랜드 힌트를 건다(같은 키를 두 줄로 나눈 이유).
    ("Converse", "chucktaylor", "Chuck Taylor All Star", ["올스타", "all star", "allstar", "척체일러", "ctas", "ct as"], ["컨버스", "converse"]),
    # "척테일러 70"은 척70이다. "척테일러"(4자)가 "척70"(3자)보다 길어 척테일러로 새던 것을 막는다.
    ("Converse", "chuck70", "Chuck 70", ["척테일러 70", "척테일러70", "chuck taylor 70", "chuck taylor all star 70", "올스타 70"], None),
    ("Converse", "jackpurcell", "Jack Purcell", ["잭퍼셀", "잭 퍼셀", "jack purcell"], None),
    # 꼼데가르송 플레이 x 척70은 일반 척70과 가격대가 다른 별개 라인이다(SB 덩크와 같은 이유로 분리).
    ("Converse", "cdgplay", "CDG Play Chuck 70", ["꼼데가르송", "꼼데 컨버스", "꼼데컨버스", "꼼데x컨버스", "꼼데 x 컨버스", "comme des garcons"], ["컨버스", "converse"]),
    ("Nike", "vapormax", "Air VaporMax", ["베이퍼맥스", "베이퍼 맥스", "vapormax", "vapor max"], None),
    ("Nike", "daybreak", "Daybreak", ["데이브레이크", "daybreak"], None),
    ("Nike", "uptempo", "Air More Uptempo", ["업템포", "uptempo"], None),
    ("Nike", "shox", "Shox", ["샥스", "shox"], None),
    ("Nike", "courtvision", "Court Vision", ["코트비전", "코트 비전", "court vision"], None),
    ("Nike", "metcon", "Metcon", ["메트콘", "metcon"], None),
    ("Nike", "alphafly", "Alphafly", ["알파플라이", "alphafly"], None),
    ("Nike", "airrift", "Air Rift", ["에어리프트", "에어 리프트", "air rift"], None),
    ("Nike", "p6000", "P-6000", ["p-6000", "p6000", "p 6000"], None),
    ("Nike", "airmaxplus", "Air Max Plus", ["에어맥스 플러스", "에어맥스플러스", "air max plus"], None),
    ("Nike", "airmaxdn", "Air Max Dn", ["에어맥스dn", "에어맥스 dn", "에어맥스던", "air max dn"], None),
    ("Nike", "jordan6", "Jordan 6", ["조던6", "조던 6", "jordan 6"], None),
    ("Nike", "jordan5", "Jordan 5", ["조던5", "조던 5", "jordan 5"], None),
    ("Nike", "jordan12", "Jordan 12", ["조던12", "조던 12", "jordan 12"], None),
    ("Nike", "jordan13", "Jordan 13", ["조던13", "조던 13", "jordan 13"], None),
    ("Adidas", "yeezy350", "Yeezy Boost 350", ["이지부스트 350", "이지부스트350", "yeezy boost 350"], None),
    ("Adidas", "yeezy700", "Yeezy Boost 700", ["이지부스트 700", "이지부스트700", "이지 700", "이지700", "yeezy 700", "yeezy boost 700"], None),
    ("Adidas", "adiospro", "Adizero Adios Pro", ["아디오스 프로", "아디오스프로", "adios pro"], None),
    ("Adidas", "tobacco", "Tobacco Gruen", ["토바코", "tobacco"], None),
    ("Adidas", "tokyo", "Tokyo", ["도쿄", "tokyo"], ["아디다스", "adidas"]),
    ("Adidas", "ultraboost", "Ultraboost", ["울트라부스트", "울트라 부스트", "ultraboost", "ultra boost"], None),
    ("Adidas", "nmd", "NMD", ["nmd"], None),
    ("Adidas", "adilette", "Adilette", ["아딜렛", "adilette"], None),
    ("Adidas", "country", "Country OG", ["컨트리 og", "country og", "컨트리"], ["아디다스", "adidas"]),
    ("Crocs", "classicclog", "Classic Clog", ["크록스 클로그", "크록스클로그", "클래식클로그", "crocs clog"], None),
    ("Crocs", "literide", "LiteRide", ["라이트라이드", "라이트 라이드", "literide"], None),
    ("Crocs", "crush", "Crush", ["크러쉬", "crush"], ["크록스", "crocs"]),
    ("Crocs", "bayaband", "Bayaband", ["바야밴드", "바야 밴드", "bayaband"], None),
    ("Crocs", "echoclog", "Echo Clog", ["에코 클로그", "에코클로그", "echo clog"], ["크록스", "crocs"]),
    ("UGG", "disquette", "Disquette", ["디스케트", "disquette"], None),
    ("UGG", "coquette", "Coquette", ["코케트", "coquette"], None),
    ("UGG", "tazz", "Tazz", ["타즈", "tazz"], ["어그", "ugg"]),
    ("UGG", "funkette", "Funkette", ["펀케트", "funkette"], None),
    ("UGG", "goldenglow", "Goldenglow", ["골든글로우", "골든 글로우", "goldenglow"], None),
    ("New Balance", "nb992", "992", ["992"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb991", "991", ["991"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb996", "996", ["996"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb878", "878", ["878"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb740", "740", ["740"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb1080", "1080", ["1080"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("Dr. Martens", "dmjadon", "Jadon", ["제이든", "jadon"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm1460", "1460", ["8홀", "8 홀"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm1461", "1461", ["3홀", "3 홀"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dmclarissa", "Clarissa", ["클라리사", "clarissa"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dmgryphon", "Gryphon", ["그리폰", "gryphon"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dmblaire", "Blaire", ["블레어", "blaire"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm1b60", "1B60", ["1b60"], None),
    ("Gucci", "horsebit", "Horsebit Loafer", ["홀스빗", "horsebit"], None),
    ("Gucci", "rhyton", "Rhyton", ["라이톤", "rhyton"], None),
    ("Gucci", "gucciace", "Ace", ["에이스", "ace"], ["구찌", "gucci"]),
    ("Prada", "monolith", "Monolith", ["모놀리스", "monolith"], None),
    ("Prada", "brushedloafer", "Brushed Leather Loafer", ["브러시드", "브러쉬드", "brushed"], ["프라다", "prada"]),
    ("Balenciaga", "triples", "Triple S", ["트리플s", "트리플 s", "트리플에스", "triple s"], None),
    ("Balenciaga", "balenciagatrack", "Track", ["트랙"], ["발렌시아가", "balenciaga"]),
    ("Asics", "superblast", "Superblast", ["슈퍼블라스트", "superblast"], None),
    ("Asics", "metaspeed", "Metaspeed", ["메타스피드", "metaspeed"], None),
    ("Asics", "gt2160", "GT-2160", ["gt-2160", "gt2160", "gt 2160"], None),
    ("Asics", "nimbus", "Gel-Nimbus", ["님버스", "nimbus"], None),
    ("Asics", "gelsonoma", "Gel-Sonoma", ["소노마", "sonoma"], None),
    ("Skechers", "slipins", "Slip-ins", ["슬립인스", "슬립 인스", "slip-ins", "slip ins"], None),
    ("Skechers", "archfit", "Arch Fit", ["아치핏", "아치 핏", "arch fit"], None),
    ("Skechers", "maxcushioning", "Max Cushioning", ["맥스쿠셔닝", "맥스 쿠셔닝", "맥스쿠션", "max cushioning"], None),
    ("FitFlop", "lulu", "Lulu", ["루루"], ["핏플랍", "fitflop"]),
    ("Rockport", "totalmotion", "Total Motion", ["토탈모션", "토탈 모션", "total motion"], None),
    ("Salomon", "xapro3d", "XA Pro 3D", ["xa pro 3d", "xa pro", "xa프로", "xa 프로"], None),
    ("Salomon", "acspro", "ACS Pro", ["acs pro", "acs+", "acs 프로", "acs프로"], None),
    ("Hoka", "mafate", "Mafate Speed", ["마파테", "mafate"], None),
    ("Hoka", "hopara", "Hopara", ["호파라", "hopara"], None),
    ("Hoka", "clifton", "Clifton", ["클리프톤", "클립톤", "clifton"], None),
    ("Hoka", "orarecovery", "Ora Recovery", ["오라 리커버리", "오라리커버리", "ora recovery"], None),
    ("Hoka", "kaha", "Kaha", ["카하", "kaha"], None),
    ("Keen", "newport", "Newport", ["뉴포트", "newport"], None),
    ("Keen", "jasper", "Jasper", ["재스퍼", "jasper"], None),
    ("Keen", "uneek", "Uneek", ["유니크", "uneek"], ["keen", " 킨", "킨 "]),
    ("Fila", "disruptor", "Disruptor", ["디스럽터", "disruptor"], None),
    ("Puma", "deviatenitro", "Deviate Nitro", ["디비에이트", "deviate"], None),
    ("Vans", "era", "Era", ["에라"], ["반스", "vans"]),
]

# 긴 별칭부터 검사해야 "에어맥스95"가 "에어맥스 9x" 계열끼리 먹히지 않는다
FLAT = sorted(
    ((alias.lower(), brand, key, hints) for brand, key, _, aliases, hints in MODELS for alias in aliases),
    key=lambda t: -len(t[0]),
)


def find_model(title, description=""):
    """제목에서만 별칭으로 모델을 찾는다. 숫자 별칭은 브랜드 힌트가 함께 있어야 한다(설명은 브랜드 힌트 확인에만 쓴다)."""
    t = (title or "").lower()
    blob = t + " " + (description or "").lower()
    for alias, brand, key, hints in FLAT:
        if alias not in t:
            continue
        if hints and not any(h in blob for h in hints):
            continue
        return brand, key
    return None
