create table department (
    id   bigserial primary key,
    name text      not null unique
);

create table employee (
    id            bigserial primary key,
    name          text           not null,
    email         text unique,
    age           integer,
    salary        numeric(12, 2),
    rating        double precision,
    active        boolean        not null default true,
    status        text,
    birth_date    date,
    hired_at      timestamp,
    created_at    timestamptz    not null default now(),
    department_id bigint references department (id)
);

create table project (
    id   bigserial primary key,
    name text      not null unique
);

-- many-to-many join table between employee and project
create table employee_project (
    employee_id bigint not null references employee (id),
    project_id  bigint not null references project (id),
    role        text   not null,
    primary key (employee_id, project_id)
);
