-- R__dev_seed.sql — development content. NEVER RUNS IN PRODUCTION.
--
-- Flyway reads this directory only when the `dev` profile is active
-- (application-dev.yaml adds classpath:db/dev-seed to spring.flyway.locations).
-- The deployed jar starts with no profile, so it reads db/migration alone and
-- this file is invisible to it. Seed data is not schema: it does not belong in
-- a versioned migration, and ADR-021 is the decision behind that split.
--
-- Repeatable (R__), so Flyway re-applies it whenever its checksum changes --
-- edit the content, restart, and the database follows. That only works if the
-- file is idempotent, which is what the DELETE below is for: every row here
-- hangs off a seed user, and app_user -> action is ON DELETE CASCADE, so
-- removing the three users removes everything this file ever created and
-- nothing a developer typed in by hand.
--
-- The content is realistic Bulgarian, not lorem ipsum, because Cyrillic
-- changes line length and rhythm and because a filter is only exercised by
-- data that actually varies (frontend brief, section 7). It deliberately
-- includes the awkward cases: an action with no external link, an expired
-- one, one of each non-published status, three regions on one action, and a
-- title at the 200-character limit.

-- ---------------------------------------------------------------------------
-- 1. Clean slate
-- ---------------------------------------------------------------------------

DELETE FROM app_user WHERE email LIKE '%@seed.5goodmins.local';


-- ---------------------------------------------------------------------------
-- 2. Three accounts, one per role
-- ---------------------------------------------------------------------------
--
-- Password for all three: `parola123`. The hash is BCrypt with the {bcrypt}
-- prefix DelegatingPasswordEncoder writes, so these log in through the normal
-- form. A published password in a published file is fine here and only here:
-- the addresses are at a domain that cannot receive mail, and this file never
-- reaches a deployed database.
--
-- email_verified_at is set, because an unverified account cannot log in
-- (AppUserDetailsService) and would be deleted by the nightly purge.

INSERT INTO app_user (email, password_hash, display_name, bio, website_url,
                      role, email_verified_at, poster_verified_at, locale, created_at)
VALUES
    ('admin@seed.5goodmins.local',
     '{bcrypt}$2a$10$.1V/MOzbDatVJc0A1.6XMuxxxeYP3uINDoo2Hu02EPduie72FJgcG',
     'Админ', 'Поддържа платформата.', NULL,
     'ADMIN', now() - interval '90 days', now() - interval '90 days', 'bg', now() - interval '90 days'),

    ('poster@seed.5goodmins.local',
     '{bcrypt}$2a$10$.1V/MOzbDatVJc0A1.6XMuxxxeYP3uINDoo2Hu02EPduie72FJgcG',
     'Зелена Варна', 'Гражданско сдружение за опазване на Черноморското крайбрежие. Работим от 2014 г.',
     'https://example.org/zelena-varna',
     'POSTER', now() - interval '60 days', now() - interval '45 days', 'bg', now() - interval '60 days'),

    ('user@seed.5goodmins.local',
     '{bcrypt}$2a$10$.1V/MOzbDatVJc0A1.6XMuxxxeYP3uINDoo2Hu02EPduie72FJgcG',
     'Мария Петрова', 'Учител по биология. Интересувам се от екология и права на животните.', NULL,
     'USER', now() - interval '30 days', NULL, 'bg', now() - interval '30 days');


-- ---------------------------------------------------------------------------
-- 3. The actions
-- ---------------------------------------------------------------------------
--
-- One VALUES list driving a loop, rather than 31 INSERT statements followed
-- by 70 more for the join tables: the categories and regions of an action are
-- written next to it, where they can be read as one row.
--
-- normalized_url is computed here by an expression that APPROXIMATES
-- ADR-011's rule (lowercase, no scheme, no www., no trailing slash). The real
-- normalization lives in the application, is more thorough, and is what runs
-- for user-submitted actions; this only has to be deterministic and unique,
-- because uq_action_normalized_url is a real constraint even on seed rows.

DO $$
DECLARE
    seeded      record;
    new_action  bigint;
BEGIN
    FOR seeded IN
        SELECT * FROM (VALUES
            -- title, description, type, external_url, video_url,
            -- categories, regions, status, published_days_ago, ends_in_days, author
            ('Спрете застрояването на дюните при Карадере',
             'Инвестиционен проект предвижда хотелски комплекс на един от последните незастроени плажове по Черноморието. Подписката ще бъде внесена в Министерството на околната среда и водите.',
             'PETITION', 'https://www.peticiya.example.org/karadere', NULL,
             ARRAY['NATURE'], ARRAY['VARNA'], 'PUBLISHED', 1, 45, 'poster'),

            ('Безопасни пешеходни пътеки пред училищата в Пловдив',
             'Три деца са пострадали на нерегулирани пътеки през последната година. Писмото иска изкуствени неравности и осветление пред всяко училище в района.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/plovdiv-uchilishta', NULL,
             ARRAY['CHILDREN', 'LOCAL_COMMUNITIES'], ARRAY['PLOVDIV'], 'PUBLISHED', 2, 30, 'user'),

            ('Анкета: достъпен ли е градският транспорт за хора с увреждания',
             'Събираме опит от хора с двигателни и зрителни увреждания за рампите, звуковите съобщения и спирките. Резултатите отиват при общинския съвет.',
             'SURVEY', 'https://example.org/anketa/transport-dostupnost', NULL,
             ARRAY['DISABILITY', 'LOCAL_COMMUNITIES'], ARRAY['SOFIA_CITY'], 'PUBLISHED', 3, 60, 'poster'),

            ('Детска млечна кухня в Кюстендил',
             'Общината закри кухнята през 2019 г. Над 200 семейства искат връщането ѝ.',
             'PETITION', 'https://www.peticiya.example.org/kyustendil-kuhnya', NULL,
             ARRAY['CHILDREN', 'SOCIAL'], ARRAY['KYUSTENDIL'], 'PUBLISHED', 4, 90, 'user'),

            ('Против изсичането в Природен парк Витоша',
             'Планът за управление допуска санитарна сеч в зони с вековни гори. Подписката подкрепя възражението на природозащитните организации.',
             'PETITION', 'https://www.peticiya.example.org/vitosha-sech', NULL,
             ARRAY['NATURE'], ARRAY['SOFIA_CITY', 'SOFIA_PROVINCE'], 'PUBLISHED', 5, 25, 'poster'),

            ('Писмо до РИОСВ за мръсния въздух в Димитровград',
             'Нормите за фини прахови частици са превишени 48 пъти тази зима. Искаме публичен график на проверките в предприятията.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/dimitrovgrad-vazduh', NULL,
             ARRAY['HEALTHCARE', 'NATURE'], ARRAY['HASKOVO'], 'PUBLISHED', 6, 40, 'user'),

            ('Общински приют за бездомни кучета в Сливен',
             'Градът няма приют, а кастрационната програма спря преди две години. Петицията предлага конкретен терен и бюджет.',
             'PETITION', 'https://www.peticiya.example.org/sliven-priyut', NULL,
             ARRAY['ANIMALS'], ARRAY['SLIVEN'], 'PUBLISHED', 7, 70, 'user'),

            ('Анкета сред медицинските сестри за натоварването в спешните отделения',
             'Десет минути. Попълва се анонимно и служи за доклад до Министерството на здравеопазването.',
             'SURVEY', 'https://example.org/anketa/spesna-pomosht', NULL,
             ARRAY['HEALTHCARE', 'LABOUR_RIGHTS'], ARRAY['NATIONAL'], 'PUBLISHED', 8, 35, 'poster'),

            ('Ремонт на отсечката Враца - Мездра',
             'Участъкът е с дупки от три години и е единственият път до болницата за седем села.',
             'PETITION', 'https://www.peticiya.example.org/vratsa-mezdra', NULL,
             ARRAY['LOCAL_COMMUNITIES'], ARRAY['VRATSA'], 'PUBLISHED', 9, 50, 'user'),

            ('Писмо до МОН за ресурсните учители',
             'Един ресурсен учител се грижи за над 20 деца в някои области. Писмото иска норматив и финансиране.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/mon-resursni', NULL,
             ARRAY['CHILDREN', 'DISABILITY'], ARRAY['NATIONAL'], 'PUBLISHED', 10, 55, 'poster'),

            ('Против закриването на болницата в Девин',
             'Най-близкото спешно отделение след закриването ще е на 60 км по планински път.',
             'PETITION', 'https://www.peticiya.example.org/devin-bolnitsa', NULL,
             ARRAY['HEALTHCARE', 'LOCAL_COMMUNITIES'], ARRAY['SMOLYAN'], 'PUBLISHED', 11, 20, 'user'),

            ('Споделете кампанията за хоспис в Русе',
             'Хосписът събира средства за десет легла. Най-полезното сега е кампанията да стигне до повече хора.',
             'SOCIAL_MEDIA', 'https://example.org/kampaniya/ruse-hospis', NULL,
             ARRAY['HEALTHCARE', 'SOCIAL'], ARRAY['RUSE'], 'PUBLISHED', 12, 28, 'poster'),

            ('Достъпни детски площадки в Бургас',
             'Нито една от 42-те общински площадки няма съоръжение за дете в количка.',
             'PETITION', 'https://www.peticiya.example.org/burgas-ploshtadki', NULL,
             ARRAY['CHILDREN', 'DISABILITY'], ARRAY['BURGAS'], 'PUBLISHED', 13, 65, 'user'),

            ('Сигнал до Комисията за защита от дискриминация',
             'Образец на писмо за отказан достъп до заведение заради етнически произход. Попълва се за пет минути.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/kzd-signal', NULL,
             ARRAY['HUMAN_RIGHTS'], ARRAY['NATIONAL'], 'PUBLISHED', 14, 120, 'poster'),

            ('Анкета за условията на труд в шивашките цехове',
             'Анонимно проучване за работното време, извънредния труд и отпуските. Данните ще бъдат предоставени на синдикатите.',
             'SURVEY', 'https://example.org/anketa/shivashki-tsehove', NULL,
             ARRAY['LABOUR_RIGHTS'], ARRAY['BLAGOEVGRAD'], 'PUBLISHED', 15, 42, 'user'),

            ('Опазване на вековните дървета в центъра на Стара Загора',
             'Проектът за нова пешеходна зона предвижда премахването на 18 дървета на възраст над 80 години.',
             'PETITION', 'https://www.peticiya.example.org/stara-zagora-darveta', NULL,
             ARRAY['NATURE', 'LOCAL_COMMUNITIES'], ARRAY['STARA_ZAGORA'], 'PUBLISHED', 16, 33, 'poster'),

            ('По-строг контрол над незаконния улов в Дунав',
             'Бракониерският улов на есетрови риби продължава въпреки забраната. Искаме повече проверки в трите крайдунавски области.',
             'PETITION', 'https://www.peticiya.example.org/dunav-ulov', NULL,
             ARRAY['ANIMALS', 'NATURE'], ARRAY['VIDIN', 'RUSE', 'SILISTRA'], 'PUBLISHED', 17, 80, 'poster'),

            ('Улично осветление в квартал без улични лампи',
             'Три улици са без осветление от десет години. Писмото е до общината и до областния управител.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/osvetlenie-kvartal', NULL,
             ARRAY['LOCAL_COMMUNITIES', 'SOCIAL'], ARRAY['PLOVDIV'], 'PUBLISHED', 18, 44, 'user'),

            ('Анкета: как поскъпването на тока се отразява на малкия бизнес',
             'За занаятчии, пекарни и малки производства. Резултатите се публикуват обобщено.',
             'SURVEY', 'https://example.org/anketa/tok-biznes', NULL,
             ARRAY['SOCIAL', 'POLITICAL'], ARRAY['NATIONAL'], 'PUBLISHED', 19, 26, 'poster'),

            ('Защита на жертвите на домашно насилие',
             'Петиция за кризисни центрове във всяка областна управа и за спешни заповеди за защита в рамките на 24 часа.',
             'PETITION', 'https://www.peticiya.example.org/zashtita-nasilie', NULL,
             ARRAY['HUMAN_RIGHTS', 'SOCIAL'], ARRAY['NATIONAL'], 'PUBLISHED', 20, 100, 'poster'),

            -- No external link: the action IS the description. The detail page
            -- needs a variant with no call-to-action button (frontend brief,
            -- page 2), and this row is the one that proves it renders.
            ('Доброволци за почистване на река Марица',
             'Събираме се в събота в 10:00 при моста на бул. Македония. Носете ръкавици; чувалите са осигурени от общината. Няма регистрация и няма външна връзка - просто елате.',
             'OTHER', NULL, NULL,
             ARRAY['NATURE', 'LOCAL_COMMUNITIES'], ARRAY['PLOVDIV'], 'PUBLISHED', 21, 12, 'user'),

            ('Запазете автобусната линия до селата край Ловеч',
             'Линията обслужва пет села без друг транспорт. Превозвачът я прекратява от следващия месец.',
             'PETITION', 'https://www.peticiya.example.org/lovech-avtobus', NULL,
             ARRAY['LOCAL_COMMUNITIES', 'SOCIAL'], ARRAY['LOVECH'], 'PUBLISHED', 22, 18, 'user'),

            ('Писмо до евродепутатите за правата на сезонните работници',
             'Образец на писмо на български и английски за условията на наемане на сезонни работници в земеделието.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/sezonni-rabotnitsi', NULL,
             ARRAY['LABOUR_RIGHTS', 'HUMAN_RIGHTS'], ARRAY['INTERNATIONAL'], 'PUBLISHED', 23, 75, 'poster'),

            ('Анкета за качеството на водата в Перник',
             'Три години след кризата питаме домакинствата какво е състоянието на водата при тях.',
             'SURVEY', 'https://example.org/anketa/pernik-voda', NULL,
             ARRAY['HEALTHCARE', 'NATURE'], ARRAY['PERNIK'], 'PUBLISHED', 24, 38, 'user'),

            ('Кастрационна програма в Добрич',
             'Програмата спря през 2022 г. и популацията на бездомните кучета се удвои.',
             'PETITION', 'https://www.peticiya.example.org/dobrich-kastratsiya', NULL,
             ARRAY['ANIMALS'], ARRAY['DOBRICH'], 'PUBLISHED', 25, 48, 'user'),

            -- The video variant: the detail page embeds a whitelisted host.
            ('Какво се случва с боклука, който изхвърляме',
             'Десетминутен филм на доброволци за пътя на отпадъците от контейнера до депото и за това колко малко от тях се рециклират.',
             'OTHER', 'https://example.org/film/boklukat', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
             ARRAY['NATURE'], ARRAY['NATIONAL'], 'PUBLISHED', 26, 150, 'poster'),

            -- A title at the 200-character limit and a long description, so the
            -- card and the detail page are designed against the worst case
            -- rather than against a short one.
            ('Петиция за общинска програма за енергийна ефективност на многофамилните жилищни сгради в областните центрове, с конкретни срокове, публичен регистър на кандидатите и ясни и публични критерии за подбор',
             'Националната програма за саниране спря, а общините нямат собствени програми. Искаме всяка общинска администрация в областен център да приеме програма с конкретни срокове и бюджет, публичен регистър на подадените заявления и критерии за подбор, които са известни предварително. Текстът на петицията е изготвен с помощта на три неправителствени организации и е съгласуван с експерти по енергийна ефективност. Подписката ще бъде внесена в Националното сдружение на общините в Република България, а копие ще получи и парламентарната комисия по енергетика. Подписването отнема по-малко от минута и изисква само име и населено място.',
             'PETITION', 'https://www.peticiya.example.org/energiyna-efektivnost', NULL,
             ARRAY['SOCIAL', 'LOCAL_COMMUNITIES', 'POLITICAL'], ARRAY['NATIONAL'], 'PUBLISHED', 27, 95, 'poster'),

            -- Expired: past its end date, so it must not appear in any listing.
            -- Still PUBLISHED, because expiry is derived and never a status.
            ('Спасете старата поща в Шумен',
             'Сградата е паметник на културата и беше обявена за продажба. Кампанията приключи - сградата остава общинска.',
             'PETITION', 'https://www.peticiya.example.org/shumen-poshta', NULL,
             ARRAY['LOCAL_COMMUNITIES'], ARRAY['SHUMEN'], 'PUBLISHED', 80, -5, 'user'),

            -- The three non-public states, visible only on "my actions" and in
            -- the admin queues (frontend brief, pages 12, 16 and 17).
            ('Нощен транспорт във Велико Търново през уикенда',
             'Предложение за две нощни линии в петък и събота, обслужващи студентските общежития.',
             'PETITION', 'https://www.peticiya.example.org/vtarnovo-noshten', NULL,
             ARRAY['LOCAL_COMMUNITIES', 'SOCIAL'], ARRAY['VELIKO_TARNOVO'], 'PENDING', NULL, 60, 'user'),

            ('Събиране на средства с неясен получател',
             'Отхвърлена от модератор: връзката води към лична банкова сметка без информация за организацията зад кампанията.',
             'OTHER', 'https://example.org/dareniya/neyasen-poluchatel', NULL,
             ARRAY['SOCIAL'], ARRAY['NATIONAL'], 'REJECTED', NULL, 30, 'user'),

            ('Ремонт на читалището в Монтана',
             'Оттеглена от автора: общината вече е заделила средства в бюджета за следващата година.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/montana-chitalishte', NULL,
             ARRAY['LOCAL_COMMUNITIES'], ARRAY['MONTANA'], 'WITHDRAWN', 35, 40, 'poster')
        ) AS t(title, description, action_type, external_url, video_url,
               categories, regions, status, published_days_ago, ends_in_days, author)
    LOOP
        INSERT INTO action (author_id, title, description, action_type,
                            external_url, normalized_url, video_url,
                            status, published_at, ends_at, created_at, updated_at)
        VALUES (
            (SELECT id FROM app_user WHERE email = seeded.author || '@seed.5goodmins.local'),
            seeded.title,
            seeded.description,
            seeded.action_type,
            seeded.external_url,
            CASE WHEN seeded.external_url IS NULL THEN NULL ELSE
                rtrim(regexp_replace(lower(seeded.external_url), '^https?://(www\.)?', ''), '/')
            END,
            seeded.video_url,
            seeded.status,
            CASE WHEN seeded.published_days_ago IS NULL THEN NULL
                 ELSE now() - make_interval(days => seeded.published_days_ago) END,
            now() + make_interval(days => seeded.ends_in_days),
            -- Created a day before it was published, or a day ago for the ones
            -- that never were. ck_action_ends_at_after_created is the
            -- constraint this has to stay on the right side of, including for
            -- the expired row above.
            now() - make_interval(days => coalesce(seeded.published_days_ago, 0) + 1),
            now() - make_interval(days => coalesce(seeded.published_days_ago, 0) + 1)
        )
        RETURNING id INTO new_action;

        INSERT INTO action_category (action_id, category)
        SELECT new_action, unnest(seeded.categories);

        INSERT INTO action_region (action_id, region)
        SELECT new_action, unnest(seeded.regions);
    END LOOP;
END $$;
