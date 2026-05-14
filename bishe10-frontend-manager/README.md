# Bishe10 Frontend Manager

Vue3 + Vite + Element Plus web 管理端，用于管理员登录和小程序用户管理。

## 启动

```bash
npm install
npm run dev
```

默认开发端口是 `5174`，开发环境会把 `/api` 代理到 `http://localhost:8080`。

## 后端接口约定

管理端默认请求这些接口：

- `POST /api/admin/auth/login`
- `GET /api/admin/auth/me`
- `POST /api/admin/auth/logout`
- `GET /api/admin/users`
- `POST /api/admin/users`
- `PUT /api/admin/users/{id}`
- `DELETE /api/admin/users/{id}`

接口可以返回项目已有的统一格式：

```json
{
  "code": 200,
  "success": true,
  "message": "success",
  "data": {}
}
```

用户分页数据支持 `records/list/items/rows/content` 作为列表字段，支持 `total/totalElements/count` 作为总数字段。

当前仓库后端已经实现了这些接口。如果你的数据库还是旧结构，先执行管理员补丁脚本：

```bash
mysql -u root -p < ../bishe10-backend-source/backend/sql/bishe10_admin_mysql.sql
```

全新数据库也可以直接执行完整脚本：

```bash
mysql -u root -p < ../bishe10-backend-source/backend/sql/bishe10_auth_mysql.sql
```

后端启动时，如果 `admin_users` 表存在但还没有管理员，会自动创建默认管理员。

默认管理员账号：

- 用户名：`admin`
- 密码：`admin123`

也可以通过后端环境变量修改：

- `BISHE10_ADMIN_USERNAME`
- `BISHE10_ADMIN_PASSWORD`
- `BISHE10_ADMIN_NICKNAME`

## 本地 mock

需要先看页面效果时，可以把 `.env.development` 中的 `VITE_USE_MOCK` 改成 `true`。

mock 管理员账号：

- 用户名：`admin`
- 密码：`admin123`
