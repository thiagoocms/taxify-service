# Monitoramento na AWS (CloudWatch)

Substitui o Prometheus + Grafana. Fica dentro da cota gratuita do CloudWatch:
10 métricas customizadas, 10 alarmes, 3 painéis e 5 GB de logs por mês.

| O quê | De onde vem | Custo |
|---|---|---|
| CPU, rede, disco (I/O), créditos de CPU, status da máquina | Métricas padrão da EC2 (`AWS/EC2`), já ligadas | grátis |
| Memória usada e disponível, espaço em disco | CloudWatch Agent → namespace `Taxify` (3 métricas) | grátis |
| Logs do backend, do MySQL e do nginx | Driver `awslogs` do Docker → grupo `/taxify` | grátis até 5 GB/mês |
| Alertas por e-mail | Alarmes do CloudWatch + tópico SNS | grátis até 10 alarmes |

Região usada nos exemplos: `us-east-2`. Troque se a EC2 estiver em outra.

## 1. Permissão da EC2 (console)

IAM → **Roles** → a role da instância (a mesma que acessa o S3) → **Add permissions** →
**Attach policies** → `CloudWatchAgentServerPolicy`.

Se a instância ainda não tiver role: EC2 → a instância → **Actions** → **Security** →
**Modify IAM role**.

## 2. CloudWatch Agent (memória e disco)

Copie `monitoring/cloudwatch-agent.json` para o servidor e instale o agente:

```bash
# Amazon Linux 2023 / 2
sudo dnf install -y amazon-cloudwatch-agent      # (AL2: sudo yum install -y amazon-cloudwatch-agent)

# Ubuntu
wget https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb
sudo dpkg -i amazon-cloudwatch-agent.deb
```

Ligue com a configuração (o agente sobe sozinho depois de um reboot):

```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 -s -c file:$(pwd)/monitoring/cloudwatch-agent.json

sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a status   # "running"
```

Em alguns minutos aparecem em CloudWatch → **Metrics** → **Taxify**:
`mem_used_percent`, `mem_available` e `disk_used_percent`.

## 3. Logs dos containers

Suba o compose com o arquivo da AWS (na pasta do `docker-compose.yml` no servidor):

```bash
# --remove-orphans derruba os containers antigos do Prometheus e do Grafana
docker compose -f docker-compose.yml -f docker-compose.aws.yml up -d --remove-orphans
```

Os logs ficam em CloudWatch → **Log groups** → `/taxify`, um stream por container.
`docker logs taxify-backend` continua funcionando.

Por padrão o grupo guarda os logs para sempre. Limite para 14 dias (console: o grupo →
**Actions** → **Edit retention setting**, ou):

```bash
aws logs put-retention-policy --log-group-name /taxify --retention-in-days 14 --region us-east-2
```

Os volumes antigos podem ser apagados: `docker volume ls` → `docker volume rm <pasta>_taxify_prometheus_data <pasta>_taxify_grafana_data`.

## 4. Alarmes por e-mail

Crie um tópico SNS e confirme o e-mail que chega (console: SNS → **Topics** → **Create
topic** → Standard → **Create subscription** → protocolo Email). Depois crie os alarmes em
CloudWatch → **Alarms** → **Create alarm**, apontando para esse tópico:

| Alarme | Métrica | Condição |
|---|---|---|
| Máquina com falha | `AWS/EC2` · `StatusCheckFailed` | ≥ 1 por 2 períodos de 5 min |
| Memória alta | `Taxify` · `mem_used_percent` | > 90 por 10 min |
| Disco quase cheio | `Taxify` · `disk_used_percent` | > 85 por 15 min |
| Créditos de CPU acabando | `AWS/EC2` · `CPUCreditBalance` | < 20 por 15 min |
| Cobrança de CPU excedente | `AWS/EC2` · `CPUSurplusCreditsCharged` | > 0 por 1 hora |
| Erros no backend (opcional) | filtro de métrica no grupo `/taxify`, padrão `ERROR`, stream `taxify-backend` | > 10 em 5 min |

Para o último: Log groups → `/taxify` → **Metric filters** → **Create metric filter** →
padrão `"ERROR"` → namespace `Taxify`, métrica `BackendErrors`, valor `1`.

## O que não é mais coletado

As métricas internas do Java (heap, tempo por endpoint, requisições), que só o Prometheus
lia em `/actuator/prometheus`. O endpoint foi removido; `/actuator/health` continua.
